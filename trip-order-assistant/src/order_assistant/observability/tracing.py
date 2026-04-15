from collections.abc import Iterator, Mapping, Sequence
from contextlib import contextmanager
from typing import Any

from opentelemetry import trace
from opentelemetry.trace import Span
from opentelemetry.util.types import AttributeValue

_tracer = trace.get_tracer(__name__)


def _coerce_attribute_value(value: Any) -> AttributeValue | None:
    if value is None:
        return None
    if isinstance(value, (str, bool, int, float)):
        return value
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        coerced_items = [_coerce_attribute_value(item) for item in value]
        if all(isinstance(item, (str, bool, int, float)) for item in coerced_items):
            return [item for item in coerced_items if item is not None]
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
    if experiment_id:
        attributes["experiment.id"] = str(experiment_id)
    fault_scenario = headers.get("fault_scenario") or headers.get("x-fault-scenario")
    if fault_scenario:
        attributes["fault.scenario"] = str(fault_scenario)
    return attributes


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
    attributes: Mapping[str, Any] | None = None,
) -> Iterator[Span]:
    span_attributes: dict[str, Any] = {
        "rpc.system": "grpc",
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
