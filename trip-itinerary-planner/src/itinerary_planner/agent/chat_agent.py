"""Conversational itinerary chat agent — ReAct edition.

Architecture
────────────
A custom 2-node LangGraph (agent → tools → agent → …) served through
ag_ui_langgraph.  The itinerary is held in the graph state so the
frontend can sync it bidirectionally via CopilotKit's useAgent hook:

  frontend state.itinerary  ←→  ChatState["itinerary"]

Flow per user turn
──────────────────
1. CopilotKit sends the current agent state (incl. itinerary and
   copilotkit.actions) — ag_ui_langgraph merges it into the graph state.
2. agent_node builds a fresh SystemMessage from state["itinerary"],
   strips stale system messages, binds backend + frontend tools, and
   calls the LLM.
3. If the LLM emits backend tool calls, ToolNode executes them.
   Frontend tool calls skip ToolNode and are routed to the browser by
   the AG-UI / CopilotKit framework.
   Every backend tool updates state["itinerary"] (and/or
   "markdown_content") atomically via a Command return + ToolMessage.
4. After the last tool call the LLM generates a text reply.
5. ag_ui_langgraph emits StateSnapshotEvent → CopilotKit → useAgent
   updates state.itinerary on the frontend in real-time.
"""

import json
import logging
from time import perf_counter
from typing import Annotated, Any, Literal, TypedDict, cast

from langchain_core.messages import AnyMessage, SystemMessage
from langchain_core.runnables import RunnableConfig
from langchain_openai import ChatOpenAI
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.graph.state import CompiledStateGraph
from langgraph.prebuilt import ToolNode
from opentelemetry import trace
from opentelemetry.trace import Status, StatusCode

from itinerary_planner.config.settings import get_settings
from itinerary_planner.nacos.naming import NacosNaming
from itinerary_planner.observability.fault import (
    force_route_decision,
    invoke_with_fault,
    should_clear_state,
)
from itinerary_planner.observability.tracing import (
    chat_turn_span,
    experiment_attributes,
)
from itinerary_planner.prompts.chat_agent import CHAT_AGENT_INSTRUCTION
from itinerary_planner.tools import (
    INLINE_TOOLS,
    geocoding_tool,
    make_plan_new_day_tool,
    make_regenerate_day_tool,
)

logger = logging.getLogger(__name__)

_SEP = "─" * 60
_MAX_ITINERARY_CHARS = 6000


def _non_empty_string(value: Any) -> str | None:
    if value is None:
        return None
    value_text = str(value).strip()
    if value_text == "":
        return None
    return value_text


def _set_current_span_attributes(attributes: dict[str, Any]) -> None:
    span = trace.get_current_span()
    if not span.is_recording():
        return
    for key, value in attributes.items():
        if value is None:
            continue
        span.set_attribute(key, value)


def _turn_attributes_from_config(config: RunnableConfig) -> dict[str, Any]:
    attributes: dict[str, Any] = {}
    configurable = config.get("configurable")
    if not isinstance(configurable, dict):
        return attributes

    attributes.update(experiment_attributes(configurable))
    headers = configurable.get("headers")
    if isinstance(headers, dict):
        attributes.update(experiment_attributes(headers))

    attr_mappings = {
        "thread_id": "chat.thread.id",
        "threadId": "chat.thread.id",
        "checkpoint_ns": "chat.checkpoint.namespace",
        "checkpoint_id": "chat.checkpoint.id",
    }
    for source_key, attr_key in attr_mappings.items():
        value_text = _non_empty_string(configurable.get(source_key))
        if value_text is not None:
            attributes[attr_key] = value_text
    return attributes


# ── State ──────────────────────────────────────────────────────────────────
# All field types are kept JSON-schema-friendly so that
# ag-ui-langgraph's schema-based state filtering includes every key.


def _keep_itinerary(
    old: dict[str, Any] | None,
    new: dict[str, Any] | None,
) -> dict[str, Any] | None:
    """Reducer that only accepts a new itinerary when it carries a real ID.

    ag_ui_langgraph may inject a schema-default empty dict (no ``id``) when the
    frontend hasn't yet pushed the itinerary into the graph state.  Without this
    guard, that empty default would overwrite a valid itinerary already stored in
    the MemorySaver checkpoint.

    F8 fault hook: ``state.itinerary=clear`` injects a state-corruption
    experiment by returning ``None``, simulating "the itinerary was wiped
    mid-turn".  The hook is a no-op when fault injection is disabled.
    """
    if should_clear_state("state.itinerary"):
        return None
    if new is not None and new.get("id"):
        return new
    return old


class ChatState(TypedDict):
    messages: Annotated[list[AnyMessage], add_messages]
    copilotkit: dict[str, Any]
    itinerary: Annotated[dict[str, Any] | None, _keep_itinerary]
    markdown_content: str
    pending_day_plan: dict[str, Any] | None


# ── System prompt builder ──────────────────────────────────────────────────


def _build_system_message(itinerary: dict[str, Any] | None) -> SystemMessage:
    """Return a SystemMessage embedding the current itinerary JSON."""
    content = CHAT_AGENT_INSTRUCTION

    if itinerary:
        dest = itinerary.get("destination", "（未知）")
        itinerary_id = itinerary.get("id")
        full_itinerary_json = json.dumps(itinerary, ensure_ascii=False)
        itinerary_json = full_itinerary_json[:_MAX_ITINERARY_CHARS]
        was_truncated = len(full_itinerary_json) > _MAX_ITINERARY_CHARS

        _set_current_span_attributes(
            {
                "itinerary.id": _non_empty_string(itinerary_id),
                "itinerary.destination": _non_empty_string(dest),
                "chat.prompt.itinerary_json_chars": len(itinerary_json),
                "chat.prompt.itinerary_json_raw_chars": len(full_itinerary_json),
                "chat.prompt.itinerary_truncated": was_truncated,
            }
        )
        content += (
            f"\n\n## ⚡ 当前用户行程（权威数据，实时注入，绝对优先）\n\n"
            f"**目的地（DESTINATION）: {dest}**\n\n"
            f"🚫 严禁为其他城市生成景点或活动。所有输出必须在 **{dest}** 范围内。\n\n"
            f"完整行程 JSON：\n\n```json\n{itinerary_json}\n```"
        )
        logger.info(
            (
                "[ChatAgent] system_prompt_built dest=%s itinerary_id=%s "
                "itinerary_chars=%d raw_chars=%d truncated=%s"
            ),
            dest,
            itinerary_id,
            len(itinerary_json),
            len(full_itinerary_json),
            was_truncated,
        )
    else:
        _set_current_span_attributes({"itinerary.present": False})
        logger.warning(
            "[ChatAgent] No itinerary in state; agent has no destination context."
        )

    return SystemMessage(content=content)


# ── Graph factory ──────────────────────────────────────────────────────────


def create_chat_graph(nacos_naming: NacosNaming | None = None) -> CompiledStateGraph:  # type: ignore[type-arg]
    """Build and compile the ReAct chat graph.

    Parameters
    ----------
    nacos_naming:
        When provided, the ``regenerate_day`` tool is included; it uses
        Nacos to discover the attraction service for fresh coordinates.
    """
    settings = get_settings()
    model = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.0,
        api_key=settings.openai.api_key,
        base_url=settings.openai.base_url,
    )

    backend_tools: list[Any] = [geocoding_tool, *INLINE_TOOLS]
    if nacos_naming is not None:
        backend_tools.append(make_regenerate_day_tool(nacos_naming))
        backend_tools.append(make_plan_new_day_tool(nacos_naming))

    tool_node = ToolNode(backend_tools)

    # ── Nodes ──────────────────────────────────────────────────────────────

    async def agent_node(state: ChatState, config: RunnableConfig) -> dict[str, Any]:
        message_count = len(state["messages"])
        itinerary = state.get("itinerary")
        frontend_tools = state.get("copilotkit", {}).get("actions", [])

        turn_attributes = _turn_attributes_from_config(config)
        turn_attributes["chat.frontend_tool_count"] = len(frontend_tools)
        turn_attributes["chat.backend_tool_count"] = len(backend_tools)

        with chat_turn_span(
            messages_count=message_count,
            itinerary=itinerary,
            attributes=turn_attributes,
        ) as turn_span:
            logger.info(_SEP)
            logger.info(
                (
                    "[ChatAgent] turn_start messages=%d itinerary=%s "
                    "frontend_tools=%d backend_tools=%d"
                ),
                message_count,
                "present" if itinerary else "absent",
                len(frontend_tools),
                len(backend_tools),
            )

            system_msg = _build_system_message(itinerary)

            # Strip any stale system messages from prior turns
            non_system = [m for m in state["messages"] if not isinstance(m, SystemMessage)]
            conversation = [system_msg, *non_system]

            # Bind backend + CopilotKit frontend tools per-request so the
            # model can invoke frontend tools registered via useFrontendTool.
            model_with_tools = model.bind_tools(backend_tools + frontend_tools)

            start = perf_counter()
            try:
                response = await invoke_with_fault(
                    "llm.chat_agent", model_with_tools, conversation, config
                )
            except Exception as exc:
                latency_ms = round((perf_counter() - start) * 1000, 2)
                turn_span.set_attribute("chat.llm.latency_ms", latency_ms)
                turn_span.set_attribute("chat.turn.outcome", "error")
                turn_span.record_exception(exc)
                turn_span.set_status(Status(StatusCode.ERROR, str(exc)))
                logger.exception(
                    "[ChatAgent] llm_invoke_failed latency_ms=%.2f", latency_ms
                )
                logger.info(_SEP)
                raise

            latency_ms = round((perf_counter() - start) * 1000, 2)
            tool_calls = getattr(response, "tool_calls", None) or []
            outcome = "tool_call" if tool_calls else "reply"

            turn_span.set_attribute("chat.llm.latency_ms", latency_ms)
            turn_span.set_attribute("chat.turn.outcome", outcome)
            turn_span.set_attribute("chat.response.tool_call_count", len(tool_calls))

            usage_metadata = getattr(response, "usage_metadata", None)
            if isinstance(usage_metadata, dict):
                for token_key in ("input_tokens", "output_tokens", "total_tokens"):
                    token_value = usage_metadata.get(token_key)
                    if isinstance(token_value, int):
                        turn_span.set_attribute(f"llm.usage.{token_key}", token_value)

            logger.info(
                (
                    "[ChatAgent] turn_complete latency_ms=%.2f "
                    "outcome=%s tool_calls=%d"
                ),
                latency_ms,
                outcome,
                len(tool_calls),
            )
            logger.info(_SEP)
            return {"messages": [response]}

    def should_continue(state: ChatState) -> Literal["tools", "__end__"]:
        last = state["messages"][-1]
        tool_calls = getattr(last, "tool_calls", None)
        if not tool_calls:
            logger.info(
                "[ChatAgent] route_decision=__end__ tool_calls=0 backend_tool_calls=0 backend_tool_names=[]"
            )
            _set_current_span_attributes(
                {
                    "chat.route.decision": "__end__",
                    "chat.route.tool_call_count": 0,
                    "chat.route.backend_tool_call_count": 0,
                }
            )
            return cast(
                Literal["tools", "__end__"],
                force_route_decision("route.should_continue", "__end__"),
            )
        frontend_names = {
            t.get("function", {}).get("name") or t.get("name")
            for t in (state.get("copilotkit") or {}).get("actions", [])
            if isinstance(t, dict)
        }
        backend_tool_names = sorted(
            tc["name"] for tc in tool_calls if tc["name"] not in frontend_names
        )
        has_backend = len(backend_tool_names) > 0
        decision: Literal["tools", "__end__"] = "tools" if has_backend else "__end__"
        decision = cast(
            Literal["tools", "__end__"],
            force_route_decision("route.should_continue", decision),
        )

        logger.info(
            (
                "[ChatAgent] route_decision=%s tool_calls=%d "
                "backend_tool_calls=%d backend_tool_names=%s"
            ),
            decision,
            len(tool_calls),
            len(backend_tool_names),
            backend_tool_names,
        )
        _set_current_span_attributes(
            {
                "chat.route.decision": decision,
                "chat.route.tool_call_count": len(tool_calls),
                "chat.route.backend_tool_call_count": len(backend_tool_names),
                "chat.route.backend_tool_names": backend_tool_names,
            }
        )
        return decision

    # ── Graph ──────────────────────────────────────────────────────────────

    workflow: StateGraph[ChatState] = StateGraph(ChatState)
    workflow.add_node("agent", agent_node)
    workflow.add_node("tools", tool_node)

    workflow.add_edge(START, "agent")
    workflow.add_conditional_edges(
        "agent",
        should_continue,
        {"tools": "tools", "__end__": END},
    )
    workflow.add_edge("tools", "agent")

    checkpointer = MemorySaver()
    graph = workflow.compile(checkpointer=checkpointer)
    logger.info(
        "[ChatAgent] ReAct graph compiled (%d backend tool(s), checkpointer=MemorySaver)",
        len(backend_tools),
    )
    return graph
