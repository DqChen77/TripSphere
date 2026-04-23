import logging

import grpc
from opentelemetry.trace import Status, StatusCode
from pydantic import BaseModel, Field
from tripsphere.attraction.v1 import attraction_pb2, attraction_pb2_grpc
from tripsphere.common.v1 import map_pb2

from itinerary_planner.nacos.naming import NacosNaming
from itinerary_planner.observability.fault import inject_fault, maybe_mutate
from itinerary_planner.observability.tracing import inject_trace_context, rpc_span

logger = logging.getLogger(__name__)


class AttractionDetail(BaseModel):
    id: str = Field(description="Attraction ID")
    name: str = Field(description="Attraction name")
    description: str = Field(description="Detailed description")
    longitude: float = Field(description="Longitude coordinate")
    latitude: float = Field(description="Latitude coordinate")
    address: str = Field(description="Full address")
    tags: list[str] = Field(default_factory=list, description="Tags/categories")


class AttractionSearchResult(BaseModel):
    attractions: list[AttractionDetail] = Field(description="List of attractions found")


async def search_attractions_nearby(
    nacos_naming: NacosNaming,
    center_longitude: float,
    center_latitude: float,
    radius_km: float = 20.0,
    tags: list[str] | None = None,
    limit: int = 50,
) -> AttractionSearchResult:
    """Search for attractions near a location using gRPC AttractionService."""
    logger.info(
        "Searching attractions near (%.4f, %.4f) within %.1fkm, tags: %s, limit: %d",
        center_longitude,
        center_latitude,
        radius_km,
        tags,
        limit,
    )

    instance = await nacos_naming.get_service_instance("trip-attraction-service")
    ip = instance.ip
    port = instance.metadata["gRPC_port"]  # pyright: ignore

    location = map_pb2.GeoPoint(latitude=center_latitude, longitude=center_longitude)
    request = attraction_pb2.GetAttractionsNearbyRequest(
        location=location, radius_meters=radius_km * 1000
    )
    target = f"{ip}:{port}"
    async with grpc.aio.insecure_channel(target) as channel:
        stub = attraction_pb2_grpc.AttractionServiceStub(channel)
        with rpc_span(
            "AttractionService",
            "GetAttractionsNearby",
            server_address=target,
            attributes={
                "tool.name": "search_attractions_nearby",
                "trip.search.radius_km": radius_km,
                "trip.search.limit": limit,
            },
        ) as span:
            try:
                async with inject_fault("rpc.attraction.GetAttractionsNearby"):
                    response = await stub.GetAttractionsNearby(
                        request,
                        metadata=list(inject_trace_context({}).items()),
                    )
            except Exception as exc:
                span.record_exception(exc)
                span.set_status(Status(StatusCode.ERROR, str(exc)))
                raise
            span.set_attribute("trip.search.result_count", len(response.attractions))

    attraction_details: list[AttractionDetail] = [
        AttractionDetail(
            id=attraction.id,
            name=attraction.name,
            description=attraction.introduction,
            latitude=attraction.location.latitude,
            longitude=attraction.location.longitude,
            address=(
                f"{attraction.address.province}, "
                f"{attraction.address.city}, {attraction.address.district}, "
                f"{attraction.address.detailed}"
            ),
            tags=list[str](attraction.tags),
        )
        for attraction in response.attractions
    ]

    return maybe_mutate(
        "tool.attractions.response",
        AttractionSearchResult(attractions=attraction_details),
    )
