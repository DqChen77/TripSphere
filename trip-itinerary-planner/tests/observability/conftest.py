"""Pytest fixtures for fault-injection tests.

Each test runs against a freshly bootstrapped :class:`FaultRegistry` and
an in-memory OpenTelemetry tracer so assertions on Span attributes /
events are deterministic and isolated.
"""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import ReadableSpan, TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from itinerary_planner.observability import fault as fault_module


@pytest.fixture(autouse=True)
def _reset_fault_registry(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """Ensure each test starts from a clean, disabled registry."""
    monkeypatch.delenv("FAULT_INJECTION_ENABLED", raising=False)
    monkeypatch.delenv("FAULT_INJECTION_SCENARIO", raising=False)
    monkeypatch.delenv("FAULT_INJECTION_FILE", raising=False)
    fault_module.FaultRegistry.reset_for_tests()
    yield
    fault_module.FaultRegistry.reset_for_tests()


@pytest.fixture
def enable_faults(monkeypatch: pytest.MonkeyPatch) -> None:
    """Switch the global fault injection flag on for a single test."""
    monkeypatch.setenv("FAULT_INJECTION_ENABLED", "true")
    fault_module.FaultRegistry.instance().bootstrap()


@pytest.fixture
def span_exporter() -> Iterator[InMemorySpanExporter]:
    """Install an in-memory tracer provider and yield its exporter."""
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    previous_provider = trace.get_tracer_provider()
    trace.set_tracer_provider(provider)
    try:
        yield exporter
    finally:
        # OpenTelemetry intentionally does not allow swapping providers
        # after the first set in production, but for tests we want to
        # restore the previous provider so subsequent test files start
        # with a clean state.  ``_TRACER_PROVIDER`` is the underlying
        # holder; mutating it is acceptable in test scope.
        trace._TRACER_PROVIDER = previous_provider  # type: ignore[attr-defined]
        exporter.clear()


def collect_attributes(spans: list[ReadableSpan], name: str) -> dict[str, object]:
    for span in spans:
        if span.name == name:
            return dict(span.attributes or {})
    raise AssertionError(f"span {name!r} not found in {[s.name for s in spans]}")
