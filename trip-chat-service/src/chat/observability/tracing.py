from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from typing import Any

from opentelemetry import trace
from opentelemetry.propagate import inject
from opentelemetry.trace import Span

_tracer = trace.get_tracer(__name__)


def _non_empty_string(value: Any) -> str | None:
    if value is None:
        return None
    value_text = str(value).strip()
    if value_text == "":
        return None
    return value_text


def _set_if_present(attributes: dict[str, Any], key: str, value: Any) -> None:
    value_text = _non_empty_string(value)
    if value_text is not None:
        attributes[key] = value_text


def experiment_attributes(headers: Mapping[str, Any] | None) -> dict[str, str]:
    if headers is None:
        return {}

    attributes: dict[str, str] = {}
    experiment_id = headers.get("experiment_id") or headers.get("x-experiment-id")
    fault_scenario = headers.get("fault_scenario") or headers.get("x-fault-scenario")
    _set_if_present(attributes, "experiment.id", experiment_id)
    _set_if_present(attributes, "fault.scenario", fault_scenario)
    return attributes


@contextmanager
def chat_entry_span(
    *,
    method: str,
    path: str,
    headers: Mapping[str, Any] | None = None,
) -> Iterator[Span]:
    attributes: dict[str, Any] = {
        "chat.entry": "true",
        "chat.request.method": method,
        "chat.request.path": path,
    }
    if headers is not None:
        _set_if_present(attributes, "enduser.id", headers.get("x-user-id"))
    attributes.update(experiment_attributes(headers))

    with _tracer.start_as_current_span("chat.entry") as span:
        for key, value in attributes.items():
            span.set_attribute(key, value)
        yield span


def enrich_current_span_with_experiment(
    headers: Mapping[str, Any] | None,
) -> None:
    if headers is None:
        return
    span = trace.get_current_span()
    if not span.is_recording():
        return
    for key, value in experiment_attributes(headers).items():
        span.set_attribute(key, value)


def build_a2a_metadata(headers: Mapping[str, Any] | None) -> dict[str, str]:
    metadata: dict[str, str] = {}
    if headers is not None:
        mappings = {
            "x-user-id": headers.get("user_id"),
            "x-user-roles": headers.get("user_roles"),
            "authorization": headers.get("authorization"),
            "x-experiment-id": headers.get("experiment_id"),
            "x-fault-scenario": headers.get("fault_scenario"),
        }
        for key, value in mappings.items():
            value_text = _non_empty_string(value)
            if value_text is not None:
                metadata[key] = value_text

    # Propagate W3C context so chat->order_assistant spans stay linked.
    inject(metadata)
    return metadata
