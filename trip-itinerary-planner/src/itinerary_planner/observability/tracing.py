from collections.abc import Iterator, Mapping, MutableMapping, Sequence
from contextlib import contextmanager
from typing import Any

from opentelemetry import trace
from opentelemetry.propagate import inject
from opentelemetry.trace import Span
from opentelemetry.util.types import AttributeValue

_tracer = trace.get_tracer(__name__)


def _non_empty_string(value: Any) -> str | None:
    if value is None:
        return None
    value_text = str(value).strip()
    if value_text == "":
        return None
    return value_text


def _coerce_attribute_value(value: Any) -> AttributeValue | None:
    if value is None:
        return None
    if isinstance(value, (str, bool, int, float)):
        return value
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        items = [_coerce_attribute_value(item) for item in value]
        if all(isinstance(item, (str, bool, int, float)) for item in items):
            return [item for item in items if item is not None]
    return str(value)


def _set_span_attributes(span: Span, attributes: Mapping[str, Any]) -> None:
    for key, value in attributes.items():
        coerced = _coerce_attribute_value(value)
        if coerced is None:
            continue
        span.set_attribute(key, coerced)


def experiment_attributes(headers: Mapping[str, Any] | None) -> dict[str, str]:
    if headers is None:
        return {}

    attributes: dict[str, str] = {}
    experiment_id = headers.get("experiment_id") or headers.get("x-experiment-id")
    fault_scenario = headers.get("fault_scenario") or headers.get("x-fault-scenario")

    experiment_id_text = _non_empty_string(experiment_id)
    if experiment_id_text is not None:
        attributes["experiment.id"] = experiment_id_text

    fault_scenario_text = _non_empty_string(fault_scenario)
    if fault_scenario_text is not None:
        attributes["fault.scenario"] = fault_scenario_text
    return attributes


@contextmanager
def chat_entry_span(
    *,
    method: str,
    path: str,
    headers: Mapping[str, Any] | None = None,
    attributes: Mapping[str, Any] | None = None,
) -> Iterator[Span]:
    span_attributes: dict[str, Any] = {
        "chat.entry": "true",
        "chat.request.method": method,
        "chat.request.path": path,
    }
    if headers is not None:
        user_id = _non_empty_string(headers.get("x-user-id"))
        if user_id is not None:
            span_attributes["enduser.id"] = user_id
    span_attributes.update(experiment_attributes(headers))
    if attributes is not None:
        span_attributes.update(attributes)

    with _tracer.start_as_current_span("chat.entry") as span:
        _set_span_attributes(span, span_attributes)
        yield span


@contextmanager
def chat_turn_span(
    *,
    messages_count: int,
    itinerary: Mapping[str, Any] | None = None,
    attributes: Mapping[str, Any] | None = None,
) -> Iterator[Span]:
    span_attributes: dict[str, Any] = {
        "chat.turn": "true",
        "chat.turn.message_count": messages_count,
        "itinerary.present": bool(itinerary),
    }
    if itinerary is not None:
        itinerary_id = _non_empty_string(itinerary.get("id"))
        destination = _non_empty_string(itinerary.get("destination"))
        if itinerary_id is not None:
            span_attributes["itinerary.id"] = itinerary_id
        if destination is not None:
            span_attributes["itinerary.destination"] = destination
    if attributes is not None:
        span_attributes.update(attributes)

    with _tracer.start_as_current_span("chat.turn") as span:
        _set_span_attributes(span, span_attributes)
        yield span


@contextmanager
def tool_span(
    tool_name: str,
    *,
    headers: Mapping[str, Any] | None = None,
    attributes: Mapping[str, Any] | None = None,
) -> Iterator[Span]:
    span_attributes: dict[str, Any] = {"tool.name": tool_name}
    span_attributes.update(experiment_attributes(headers))
    if attributes is not None:
        span_attributes.update(attributes)

    with _tracer.start_as_current_span(f"tool.{tool_name}") as span:
        _set_span_attributes(span, span_attributes)
        yield span


@contextmanager
def rpc_span(
    service: str,
    method: str,
    *,
    headers: Mapping[str, Any] | None = None,
    server_address: str | None = None,
    rpc_system: str = "grpc",
    attributes: Mapping[str, Any] | None = None,
) -> Iterator[Span]:
    span_attributes: dict[str, Any] = {
        "rpc.system": rpc_system,
        "rpc.service": service,
        "rpc.method": method,
    }
    if server_address is not None:
        span_attributes["server.address"] = server_address
    span_attributes.update(experiment_attributes(headers))
    if attributes is not None:
        span_attributes.update(attributes)

    with _tracer.start_as_current_span(f"rpc.{service}.{method}") as span:
        _set_span_attributes(span, span_attributes)
        yield span


def inject_trace_context(
    carrier: MutableMapping[str, str] | None = None,
) -> dict[str, str]:
    metadata: dict[str, str] = {}
    if carrier is not None:
        metadata.update(carrier)
    inject(metadata)
    return metadata
