import asyncio
import logging
import warnings
from typing import Any

from a2a.types import Message as A2AMessage
from google.adk.agents.invocation_context import InvocationContext
from google.adk.agents.remote_a2a_agent import RemoteA2aAgent

from chat.nacos.ai import NacosAI
from chat.observability.fault import should_drop
from chat.observability.tracing import (
    build_a2a_metadata,
    enrich_current_span_with_experiment,
)

# Suppress ADK Experimental Warnings
warnings.filterwarnings("ignore", module=".*")

logger = logging.getLogger(__name__)


def a2a_request_meta_provider(
    ctx: InvocationContext, message: A2AMessage
) -> dict[str, Any]:
    headers: dict[str, Any] = ctx.session.state.get("headers", {})
    enrich_current_span_with_experiment(headers)
    metadata = build_a2a_metadata(headers)
    # F10 fault hook: ``a2a.trace=drop`` simulates a broken trace context
    # propagation so we can validate that observability gaps are detected
    # by the AgentOps pipeline.  Strips W3C / baggage keys but keeps the
    # business headers so the remote agent still sees the experiment id.
    if should_drop("a2a.trace", headers=headers):
        for key in ("traceparent", "tracestate", "baggage"):
            metadata.pop(key, None)
    return metadata


class RemoteAgentsFactory:
    # Default remote agent names to discover via Nacos
    _DEFAULT_REMOTE_AGENTS = ["order_assistant"]

    def __init__(
        self, nacos_ai: NacosAI, *, remote_agents: list[str] | None = None
    ) -> None:
        self._nacos_ai = nacos_ai
        self._remote_agent_names = remote_agents or self._DEFAULT_REMOTE_AGENTS

    async def get_remote_agents(self) -> list[RemoteA2aAgent]:
        tasks: list[asyncio.Task[RemoteA2aAgent | None]] = []
        async with asyncio.TaskGroup() as group:
            for name in self._remote_agent_names:
                tasks.append(group.create_task(self._resolve_remote_agent(name)))
        return [agent for task in tasks if (agent := task.result()) is not None]

    async def _resolve_remote_agent(self, agent_name: str) -> RemoteA2aAgent | None:
        # F4/F10 fault hook: ``agent.<name>=drop`` simulates the remote
        # sub-agent disappearing (Nacos AI returns nothing) so the root
        # agent has to fall back without delegation.  When fault injection
        # is disabled this is a near-zero-cost branch.
        if should_drop(f"agent.{agent_name}"):
            logger.warning(
                "remote agent '%s' dropped by fault injection", agent_name
            )
            return None
        try:
            agent_card = await self._nacos_ai.get_agent_card(agent_name)
        except Exception:
            logger.exception("Failed to resolve remote agent '%s'", agent_name)
            return None
        logger.debug(f"Resolved remote agent '{agent_name}': {agent_card}")
        return RemoteA2aAgent(
            name=agent_card.name,
            agent_card=agent_card,
            a2a_request_meta_provider=a2a_request_meta_provider,
            # use_legacy=False,
        )
