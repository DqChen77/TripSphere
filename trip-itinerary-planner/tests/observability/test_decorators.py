"""Behavioural tests for the runtime fault primitives."""

from __future__ import annotations

import asyncio
import time
from typing import Any
from unittest.mock import AsyncMock

import grpc
import pytest
from grpc.aio import AioRpcError
from opentelemetry import trace
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from pydantic import BaseModel

from itinerary_planner.observability.fault import (
    FaultPrimitive,
    FaultSpec,
    force_route_decision,
    inject_fault,
    invoke_with_fault,
    maybe_mutate,
    reset_fault_context,
    set_fault_context,
    should_clear_state,
    should_drop,
)


def _tracer() -> trace.Tracer:
    return trace.get_tracer("tripsphere.tests")


# ─────────────────────────────────────────────────────────────────────────────
# inject_fault: fast path when disabled
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_inject_fault_is_noop_when_disabled() -> None:
    start = time.perf_counter()
    async with inject_fault("tool.geocoding"):
        pass
    assert time.perf_counter() - start < 0.05


# ─────────────────────────────────────────────────────────────────────────────
# inject_fault: latency
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_inject_fault_latency_sleeps(
    enable_faults: None,
    span_exporter: InMemorySpanExporter,
) -> None:
    headers = {"x-fault-scenario": "tool.geocoding.latency=120"}
    token = set_fault_context(headers)
    try:
        with _tracer().start_as_current_span("tool.geocoding"):
            start = time.perf_counter()
            async with inject_fault("tool.geocoding"):
                pass
            elapsed = time.perf_counter() - start
    finally:
        reset_fault_context(token)
    assert elapsed >= 0.1
    spans = span_exporter.get_finished_spans()
    attrs = dict(spans[0].attributes or {})
    assert attrs["fault.injected"] is True
    assert attrs["fault.primitive"] == "latency"
    assert attrs["fault.outcome"] == "delayed"


# ─────────────────────────────────────────────────────────────────────────────
# inject_fault: exception / grpc_error
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_inject_fault_exception_raises_chosen_class(
    enable_faults: None,
) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "tool.geocoding.exception=ValueError,message=boom"}
    )
    try:
        with pytest.raises(ValueError, match="boom"):
            async with inject_fault("tool.geocoding"):
                pass
    finally:
        reset_fault_context(token)


@pytest.mark.asyncio
async def test_inject_fault_grpc_error_raises_aio_rpc_error(
    enable_faults: None,
) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "rpc.itinerary.create.error=UNAVAILABLE,message=down"}
    )
    try:
        with pytest.raises(AioRpcError) as exc_info:
            async with inject_fault("rpc.itinerary.create"):
                pass
    finally:
        reset_fault_context(token)
    assert exc_info.value.code() is grpc.StatusCode.UNAVAILABLE


@pytest.mark.asyncio
async def test_unknown_exception_falls_back_to_runtime_error(
    enable_faults: None,
) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "tool.geocoding.exception=NotARealClass"}
    )
    try:
        with pytest.raises(RuntimeError):
            async with inject_fault("tool.geocoding"):
                pass
    finally:
        reset_fault_context(token)


# ─────────────────────────────────────────────────────────────────────────────
# Probability gate
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_probability_zero_never_triggers(enable_faults: None) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "tool.geocoding.exception=RuntimeError,probability=0"}
    )
    try:
        async with inject_fault("tool.geocoding"):
            pass
    finally:
        reset_fault_context(token)


# ─────────────────────────────────────────────────────────────────────────────
# maybe_mutate
# ─────────────────────────────────────────────────────────────────────────────


class _Bag(BaseModel):
    items: list[str]


def test_maybe_mutate_truncates_field(enable_faults: None) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "tool.bag.response.mutate=truncate,n=1,field=items"}
    )
    try:
        bag = _Bag(items=["a", "b", "c"])
        mutated = maybe_mutate("tool.bag.response", bag)
    finally:
        reset_fault_context(token)
    assert mutated.items == ["a"]


def test_maybe_mutate_with_custom_mutator(enable_faults: None) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "tool.bag.response.mutate=clear_all"}
    )

    def mutator(value: _Bag, spec: FaultSpec) -> _Bag:
        assert spec.primitive is FaultPrimitive.MUTATE
        value.items = []
        return value

    try:
        bag = _Bag(items=["a", "b"])
        mutated = maybe_mutate("tool.bag.response", bag, mutator=mutator)
    finally:
        reset_fault_context(token)
    assert mutated.items == []


def test_maybe_mutate_is_noop_without_specs(enable_faults: None) -> None:
    bag = _Bag(items=["a"])
    assert maybe_mutate("tool.bag.response", bag) is bag


# ─────────────────────────────────────────────────────────────────────────────
# State / route / drop primitives
# ─────────────────────────────────────────────────────────────────────────────


def test_should_clear_state_obeys_dsl(enable_faults: None) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "state.pending_day_plan.clear=true"}
    )
    try:
        assert should_clear_state("state.pending_day_plan") is True
        assert should_clear_state("state.itinerary") is False
    finally:
        reset_fault_context(token)


def test_force_route_decision_returns_default_without_fault(
    enable_faults: None,
) -> None:
    assert force_route_decision("route.should_continue", "tools") == "tools"


def test_force_route_decision_overrides(enable_faults: None) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "route.should_continue.force_route=__end__"}
    )
    try:
        assert force_route_decision("route.should_continue", "tools") == "__end__"
    finally:
        reset_fault_context(token)


def test_should_drop_obeys_dsl(enable_faults: None) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "agent.order_assistant.drop=true"}
    )
    try:
        assert should_drop("agent.order_assistant") is True
        assert should_drop("agent.weather") is False
    finally:
        reset_fault_context(token)


# ─────────────────────────────────────────────────────────────────────────────
# invoke_with_fault end-to-end
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_invoke_with_fault_runs_runnable(enable_faults: None) -> None:
    runnable = AsyncMock()
    runnable.ainvoke.return_value = "ok"
    result = await invoke_with_fault("llm.test", runnable, "prompt")
    assert result == "ok"
    runnable.ainvoke.assert_awaited_once_with("prompt")


@pytest.mark.asyncio
async def test_invoke_with_fault_propagates_pre_call_exception(
    enable_faults: None,
) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "llm.test.exception=RuntimeError,message=fail"}
    )
    runnable = AsyncMock()
    runnable.ainvoke.return_value = "should not see"
    try:
        with pytest.raises(RuntimeError, match="fail"):
            await invoke_with_fault("llm.test", runnable, "prompt", headers={})
    finally:
        reset_fault_context(token)
    runnable.ainvoke.assert_not_called()


@pytest.mark.asyncio
async def test_invoke_with_fault_post_call_mutation(enable_faults: None) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "llm.test.response.mutate=set,field=content,to=fake"}
    )
    runnable = AsyncMock()

    class _Reply:
        def __init__(self) -> None:
            self.content = "real"

    runnable.ainvoke.return_value = _Reply()
    try:
        result: Any = await invoke_with_fault("llm.test", runnable, "prompt")
    finally:
        reset_fault_context(token)
    assert result.content == "fake"


# ─────────────────────────────────────────────────────────────────────────────
# Concurrent isolation
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_context_is_isolated_between_tasks(enable_faults: None) -> None:
    """Two concurrent requests must not see each other's fault context."""

    captured: dict[str, str | None] = {}

    async def request(name: str, scenario: str) -> None:
        token = set_fault_context({"x-experiment-id": name, "x-fault-scenario": scenario})
        try:
            await asyncio.sleep(0.01)
            from itinerary_planner.observability.fault import get_fault_context

            ctx = get_fault_context()
            captured[name] = ctx.experiment_id if ctx else None
        finally:
            reset_fault_context(token)

    await asyncio.gather(
        request("exp-1", "tool.geocoding.latency=10"),
        request("exp-2", "tool.geocoding.latency=20"),
    )
    assert captured == {"exp-1": "exp-1", "exp-2": "exp-2"}
