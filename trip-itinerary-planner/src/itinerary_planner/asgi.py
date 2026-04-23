import logging
from collections.abc import Awaitable, Callable
from contextlib import asynccontextmanager
from typing import AsyncGenerator

from ag_ui_langgraph import LangGraphAgent, add_langgraph_fastapi_endpoint
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from openinference.instrumentation.langchain import LangChainInstrumentor
from opentelemetry.trace import Status, StatusCode
from starlette.responses import Response

from itinerary_planner.agent.chat_agent import create_chat_graph
from itinerary_planner.config.logging import setup_logging
from itinerary_planner.config.settings import get_settings
from itinerary_planner.grpc.clients.itinerary import ItineraryServiceClient
from itinerary_planner.nacos.naming import NacosNaming
from itinerary_planner.observability.fault import (
    FaultRegistry,
    reset_fault_context,
    set_fault_context,
)
from itinerary_planner.observability.tracing import chat_entry_span
from itinerary_planner.routers.planning import planning

logger = logging.getLogger(__name__)

setup_logging()
LangChainInstrumentor().instrument()


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    settings = get_settings()
    logger.info("Loaded settings: %s", settings)

    FaultRegistry.instance().bootstrap()

    try:
        app.state.nacos_naming = await NacosNaming.create_naming(
            service_name=settings.app.name,
            port=settings.uvicorn.port,
            server_address=settings.nacos.server_address,
            namespace_id=settings.nacos.namespace_id,
        )
        logger.info("Registering service instance...")
        await app.state.nacos_naming.register(ephemeral=True)

        app.state.itinerary_service_client = ItineraryServiceClient(
            nacos_naming=app.state.nacos_naming
        )

        # CopilotKit AG-UI endpoint
        chat_graph = create_chat_graph(nacos_naming=app.state.nacos_naming)
        chat_agent = LangGraphAgent(name="itinerary_planner", graph=chat_graph)
        add_langgraph_fastapi_endpoint(app, chat_agent, "/")
        yield
    except Exception as e:
        logger.error("Error during lifespan startup: %s", e)
        raise
    finally:
        if isinstance(app.state.nacos_naming, NacosNaming):
            logger.info("Deregistering service instance...")
            await app.state.nacos_naming.deregister(ephemeral=True)
            await app.state.nacos_naming.shutdown()


def create_fastapi_app() -> FastAPI:
    app_settings = get_settings().app
    app = FastAPI(debug=app_settings.debug, lifespan=lifespan)

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["http://localhost:3000"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.middleware("http")
    async def chat_entry_observability_middleware(
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        # Always install a fault context so any handler (chat or REST)
        # can see the experiment / fault headers carried on this request.
        fault_token = set_fault_context(request.headers)
        try:
            if request.url.path != "/":
                return await call_next(request)

            with chat_entry_span(
                method=request.method,
                path=request.url.path,
                headers=request.headers,
            ) as span:
                try:
                    response = await call_next(request)
                except Exception as exc:
                    span.record_exception(exc)
                    span.set_status(Status(StatusCode.ERROR, str(exc)))
                    raise

                span.set_attribute("http.response.status_code", response.status_code)
                if response.status_code >= 500:
                    span.set_status(Status(StatusCode.ERROR))
                return response
        finally:
            reset_fault_context(fault_token)

    app.include_router(planning, prefix="/api/v1")
    return app


app = create_fastapi_app()
