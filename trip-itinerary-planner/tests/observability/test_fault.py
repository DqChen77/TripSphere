"""Tests for the DSL parser, registry and request context primitives."""

from __future__ import annotations

import pytest

from itinerary_planner.observability import fault as fault_module
from itinerary_planner.observability.fault import (
    FaultPrimitive,
    FaultRegistry,
    FaultSpec,
    get_fault_context,
    is_enabled,
    parse_scenario,
    reset_fault_context,
    set_fault_context,
)

# ─────────────────────────────────────────────────────────────────────────────
# DSL parsing
# ─────────────────────────────────────────────────────────────────────────────


def test_parse_simple_latency() -> None:
    specs = parse_scenario("tool.geocoding.latency=8000")
    assert specs == [
        FaultSpec(
            target="tool.geocoding",
            primitive=FaultPrimitive.LATENCY,
            params={"value": 8000},
        )
    ]


def test_parse_grpc_error_with_extra_params() -> None:
    specs = parse_scenario("rpc.itinerary.create.error=UNAVAILABLE,message=boom")
    assert len(specs) == 1
    spec = specs[0]
    assert spec.target == "rpc.itinerary.create"
    assert spec.primitive is FaultPrimitive.GRPC_ERROR
    assert spec.params == {"value": "UNAVAILABLE", "message": "boom"}


def test_parse_multiple_chunks() -> None:
    specs = parse_scenario(
        "tool.geocoding.latency=3000;state.itinerary.clear=true;route.should_continue.force_route=__end__"
    )
    targets = sorted(s.target for s in specs)
    assert targets == [
        "route.should_continue",
        "state.itinerary",
        "tool.geocoding",
    ]


def test_parse_probability_and_experiment_id() -> None:
    specs = parse_scenario(
        "tool.geocoding.latency=1000,probability=0.5,experiment_id=exp-123"
    )
    assert specs[0].probability == pytest.approx(0.5)
    assert specs[0].experiment_id == "exp-123"


@pytest.mark.parametrize(
    "raw",
    ["", "  ", "garbage", "tool.geocoding.unknownprim=1", "tool.geocoding.latency"],
)
def test_parse_skips_invalid_chunks(raw: str) -> None:
    assert parse_scenario(raw) == []


# ─────────────────────────────────────────────────────────────────────────────
# Registry bootstrap
# ─────────────────────────────────────────────────────────────────────────────


def test_registry_disabled_by_default() -> None:
    FaultRegistry.instance().bootstrap()
    assert FaultRegistry.instance().is_enabled() is False
    assert FaultRegistry.instance().specs_for("tool.geocoding") == []


def test_registry_loads_env_scenario(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FAULT_INJECTION_ENABLED", "true")
    monkeypatch.setenv(
        "FAULT_INJECTION_SCENARIO",
        "tool.geocoding.latency=2000;rpc.itinerary.create.error=UNAVAILABLE",
    )
    FaultRegistry.reset_for_tests()
    FaultRegistry.instance().bootstrap()
    reg = FaultRegistry.instance()
    assert reg.is_enabled()
    geo = reg.specs_for("tool.geocoding")
    assert len(geo) == 1 and geo[0].primitive is FaultPrimitive.LATENCY
    rpc = reg.specs_for("rpc.itinerary.create")
    assert rpc and rpc[0].primitive is FaultPrimitive.GRPC_ERROR


def test_registry_loads_json_file(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config = tmp_path / "faults.json"
    config.write_text(
        """[
            {
                "target": "tool.geocoding",
                "primitive": "latency",
                "params": {"value": 1500},
                "probability": 1.0
            },
            {"target": "agent.order_assistant", "primitive": "drop"}
        ]""",
        encoding="utf-8",
    )
    monkeypatch.setenv("FAULT_INJECTION_ENABLED", "1")
    monkeypatch.setenv("FAULT_INJECTION_FILE", str(config))
    FaultRegistry.reset_for_tests()
    FaultRegistry.instance().bootstrap()
    reg = FaultRegistry.instance()
    assert reg.specs_for("tool.geocoding")[0].params == {"value": 1500}
    assert reg.specs_for("agent.order_assistant")[0].primitive is FaultPrimitive.DROP


def test_registry_handles_corrupt_file(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config = tmp_path / "broken.json"
    config.write_text("{not json", encoding="utf-8")
    monkeypatch.setenv("FAULT_INJECTION_ENABLED", "true")
    monkeypatch.setenv("FAULT_INJECTION_FILE", str(config))
    FaultRegistry.reset_for_tests()
    # bootstrap must not raise even when the file is broken
    FaultRegistry.instance().bootstrap()
    assert FaultRegistry.instance().is_enabled()


# ─────────────────────────────────────────────────────────────────────────────
# ContextVar isolation
# ─────────────────────────────────────────────────────────────────────────────


def test_context_extracts_experiment_and_scenario(enable_faults: None) -> None:
    token = set_fault_context(
        {
            "x-experiment-id": "exp-abc",
            "x-fault-scenario": "tool.geocoding.latency=500",
        }
    )
    try:
        ctx = get_fault_context()
        assert ctx is not None
        assert ctx.experiment_id == "exp-abc"
        assert len(ctx.scenario_specs) == 1
        assert ctx.scenario_specs[0].target == "tool.geocoding"
    finally:
        reset_fault_context(token)
    assert get_fault_context() is None


def test_context_resolution_merges_registry_and_headers(
    enable_faults: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAULT_INJECTION_SCENARIO", "tool.geocoding.latency=100")
    FaultRegistry.reset_for_tests()
    FaultRegistry.instance().bootstrap()

    token = set_fault_context(
        {"x-fault-scenario": "tool.geocoding.exception=RuntimeError"}
    )
    try:
        specs = fault_module._resolve_specs_for("tool.geocoding")
    finally:
        reset_fault_context(token)
    primitives = sorted(s.primitive.value for s in specs)
    assert primitives == ["exception", "latency"]


def test_is_enabled_reflects_registry(enable_faults: None) -> None:
    assert is_enabled() is True
