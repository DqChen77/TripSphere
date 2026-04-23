"""Smoke tests for the chat-service mirror of the fault framework."""

from __future__ import annotations

import pytest

from chat.observability.fault import (
    FaultPrimitive,
    inject_fault,
    parse_scenario,
    reset_fault_context,
    set_fault_context,
    should_drop,
)


def test_parse_drop_primitive() -> None:
    specs = parse_scenario("agent.order_assistant.drop=true")
    assert len(specs) == 1
    assert specs[0].primitive is FaultPrimitive.DROP
    assert specs[0].target == "agent.order_assistant"


def test_should_drop_uses_request_context(enable_faults: None) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "agent.order_assistant.drop=true"}
    )
    try:
        assert should_drop("agent.order_assistant") is True
        assert should_drop("agent.weather") is False
    finally:
        reset_fault_context(token)


@pytest.mark.asyncio
async def test_inject_fault_disabled_is_noop() -> None:
    async with inject_fault("rpc.product.GetSkuById"):
        pass


@pytest.mark.asyncio
async def test_inject_fault_exception_chat(enable_faults: None) -> None:
    token = set_fault_context(
        {"x-fault-scenario": "rpc.product.GetSkuById.exception=RuntimeError"}
    )
    try:
        with pytest.raises(RuntimeError):
            async with inject_fault("rpc.product.GetSkuById"):
                pass
    finally:
        reset_fault_context(token)
