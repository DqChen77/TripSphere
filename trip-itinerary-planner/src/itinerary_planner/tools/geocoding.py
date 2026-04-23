"""Geocoding tool — shared by workflow and chat agent (single @tool)."""

import logging

from httpx import AsyncClient
from langchain_core.tools import tool
from opentelemetry.trace import Status, StatusCode
from pydantic import BaseModel, Field

from itinerary_planner.observability.fault import inject_fault, maybe_mutate
from itinerary_planner.observability.tracing import inject_trace_context, rpc_span

logger = logging.getLogger(__name__)


class GeocodeResult(BaseModel):
    """Result of geocoding a location."""

    name: str = Field(description="Location name")
    latitude: float = Field(description="Latitude coordinate")
    longitude: float = Field(description="Longitude coordinate")
    address: str = Field(description="Full address or description")


@tool
async def geocoding_tool(address: str, city: str = "") -> GeocodeResult:
    """Convert a location name to geographic coordinates (latitude, longitude).
    Arguments:
        address: Name or address of the location to geocode
        city: Optional city context (e.g., "Shanghai", "Beijing")

    Returns:
        GeocodeResult with coordinates and address information
    Use when the user asks for coordinates or address of a place.
    """
    logger.info("Geocoding: %s (city: %s)", address, city)

    endpoint = "https://restapi.amap.com/v3/geocode/geo"
    params = {
        "key": "90b08d9c9b136bf3543d05181b86cf5c",
        "address": address,
        "city": city,
    }
    async with AsyncClient() as client:
        with rpc_span(
            "AmapGeocoding",
            "v3.geocode.geo",
            rpc_system="http",
            server_address="restapi.amap.com",
            attributes={"tool.name": "geocoding_tool", "geocode.city": city or ""},
        ) as span:
            try:
                async with inject_fault("tool.geocoding"):
                    response = await client.get(
                        endpoint,
                        params=params,
                        headers=inject_trace_context({}),
                    )
                span.set_attribute("http.response.status_code", response.status_code)
                response.raise_for_status()
            except Exception as exc:
                span.record_exception(exc)
                span.set_status(Status(StatusCode.ERROR, str(exc)))
                raise

            data = response.json()
            if data["status"] == "1" and data["infocode"] == "10000":
                longitude, latitude = data["geocodes"][0]["location"].split(",")
                result = GeocodeResult(
                    name=data["geocodes"][0]["formatted_address"],
                    latitude=float(latitude),
                    longitude=float(longitude),
                    address=data["geocodes"][0]["formatted_address"],
                )
                return maybe_mutate("tool.geocoding.response", result)

            span.set_status(Status(StatusCode.ERROR, data.get("info", "unknown_error")))
            logger.error("Geocoding failed: %s", data["info"])
            raise ValueError(f"Geocoding failed: {data['info']}")
