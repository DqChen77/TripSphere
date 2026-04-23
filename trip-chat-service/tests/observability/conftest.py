"""Pytest fixtures mirroring trip-itinerary-planner/tests/observability."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from chat.observability import fault as fault_module


@pytest.fixture(autouse=True)
def _reset_fault_registry(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    monkeypatch.delenv("FAULT_INJECTION_ENABLED", raising=False)
    monkeypatch.delenv("FAULT_INJECTION_SCENARIO", raising=False)
    monkeypatch.delenv("FAULT_INJECTION_FILE", raising=False)
    fault_module.FaultRegistry.reset_for_tests()
    yield
    fault_module.FaultRegistry.reset_for_tests()


@pytest.fixture
def enable_faults(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FAULT_INJECTION_ENABLED", "true")
    fault_module.FaultRegistry.instance().bootstrap()
