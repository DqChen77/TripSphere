import json
import os
import warnings
from typing import Optional

from a2a.utils.constants import AGENT_CARD_WELL_KNOWN_PATH
from ag_ui_adk import AGUIToolset  # pyright: ignore  # noqa: F401
from google.adk.agents import LlmAgent
from google.adk.agents.callback_context import CallbackContext
from google.adk.agents.remote_a2a_agent import RemoteA2aAgent
from google.adk.apps import App, ResumabilityConfig
from google.adk.models.lite_llm import LiteLlm
from google.adk.models.llm_request import LlmRequest
from google.adk.models.llm_response import LlmResponse
from google.adk.tools.load_memory_tool import load_memory_tool
from google.adk.tools.mcp_tool import McpToolset, StdioConnectionParams
from mcp import StdioServerParameters

from chat.config.settings import get_settings
from chat.prompts.agent import DELEGATOR_INSTRUCTION

warnings.filterwarnings("ignore", module="google.adk")

openai_settings = get_settings().openai
if not os.environ.get("OPENAI_API_KEY"):
    os.environ["OPENAI_API_KEY"] = openai_settings.api_key.get_secret_value()
if not os.environ.get("OPENAI_BASE_URL"):
    os.environ["OPENAI_BASE_URL"] = openai_settings.base_url


# NOTE: Wait for AGUI to fix the deep copy issue of
# McpToolset with StdioConnectionParams.
def create_weather_toolset() -> McpToolset:
    return McpToolset(
        connection_params=StdioConnectionParams(
            server_params=StdioServerParameters(
                command="python", args=["-m", "mcp_weather_server"]
            )
        )
    )


def _extract_instruction_text(
    instruction: object,
) -> str:
    """Extract plain text from a system instruction of any supported type."""
    if instruction is None:
        return ""
    if isinstance(instruction, str):
        return instruction
    # google.genai.types.Content
    parts = getattr(instruction, "parts", None)
    if parts:
        first_text = getattr(parts[0], "text", None)
        if first_text:
            return str(first_text)
    return str(instruction)


def _inject_page_context(
    callback_context: CallbackContext, llm_request: LlmRequest
) -> Optional[LlmResponse]:
    """Inject frontend page context (e.g. hotel details) into the system prompt.

    Context provided via CopilotKit's useAgentContext is stored
    by ag_ui_adk under the ``_ag_ui_context`` session-state key as a
    list of ``{"description": ..., "value": ...}`` dicts.
    """
    ag_ui_context: list[dict[str, object]] = (
        callback_context.state.get("_ag_ui_context") or []
    )
    if not ag_ui_context:
        return None

    parts: list[str] = []
    for ctx in ag_ui_context:
        desc = ctx.get("description", "")
        value = ctx.get("value")
        if value is not None:
            value_str = (
                json.dumps(value, ensure_ascii=False, indent=2)
                if not isinstance(value, str)
                else value
            )
            parts.append(f"[{desc}]\n{value_str}")

    if not parts:
        return None

    suffix = "\n\n" + "\n\n".join(parts) + "\n\n请利用以上信息准确回答用户的问题。"

    existing = _extract_instruction_text(llm_request.config.system_instruction)  # pyright: ignore[reportUnknownMemberType, reportUnknownArgumentType]
    llm_request.config.system_instruction = existing + suffix  # pyright: ignore[reportAttributeAccessIssue]
    return None


def create_agent(agui: bool = False) -> LlmAgent:
    order_assistant = RemoteA2aAgent(
        name="order_assistant",
        agent_card=(
            f"http://localhost:8000/a2a/order_assistant{AGENT_CARD_WELL_KNOWN_PATH}"
        ),
    )
    return LlmAgent(
        name="chat",
        model=LiteLlm(model="openai/gpt-4o"),
        instruction=DELEGATOR_INSTRUCTION,
        sub_agents=[order_assistant],
        tools=[load_memory_tool, AGUIToolset()],
        before_model_callback=_inject_page_context,
    )


root_agent = create_agent()

app = App(
    name="chat",
    root_agent=root_agent,
    # Set the resumability config to enable resumability.
    resumability_config=ResumabilityConfig(is_resumable=True),
)
