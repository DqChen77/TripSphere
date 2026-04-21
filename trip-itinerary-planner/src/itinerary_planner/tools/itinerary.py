"""Backend tools for itinerary modification used by the ReAct chat agent.

Each tool uses InjectedState to read the current itinerary from the graph
state and returns a Command that atomically updates `itinerary` (and/or
`markdown_content`) together with a properly-formed ToolMessage so the LLM
receives a useful acknowledgement.

The itinerary is stored as a plain `dict[str, Any]` (the JSON-serializable
form) so it survives checkpointing and AG-UI state-snapshot events without
custom serialisation.
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timedelta
from time import perf_counter
from typing import Annotated, Any

from langchain_core.messages import ToolMessage
from langchain_core.tools import tool
from langchain_core.tools.base import InjectedToolCallId
from langgraph.prebuilt import InjectedState
from langgraph.types import Command
from pydantic import BaseModel, Field

from itinerary_planner.config.settings import get_settings
from itinerary_planner.nacos.naming import NacosNaming
from itinerary_planner.observability.tracing import tool_span
from itinerary_planner.tools.attractions import search_attractions_nearby
from itinerary_planner.tools.hotel import search_hotels_nearby

logger = logging.getLogger(__name__)

_FALLBACK_LONGITUDE = 121.4737
_FALLBACK_LATITUDE = 31.2304


# ── Helpers ────────────────────────────────────────────────────────────────


def _add_days(date_str: str, days: int) -> str:
    """Add *days* to a YYYY-MM-DD string; returns YYYY-MM-DD."""
    d = datetime.fromisoformat(date_str) + timedelta(days=days)
    return d.strftime("%Y-%m-%d")


def _normalize_activity(activity: dict[str, Any]) -> dict[str, Any]:
    location = activity.get("location") or {}
    estimated_cost = activity.get("estimated_cost") or {}
    return {
        **activity,
        "id": activity.get("id") or f"activity-{uuid.uuid4().hex[:8]}",
        "description": activity.get("description") or "",
        "category": activity.get("category") or "sightseeing",
        "kind": activity.get("kind") or "custom",
        "attraction_id": activity.get("attraction_id"),
        "hotel_id": activity.get("hotel_id"),
        "location": {
            "name": location.get("name") or activity.get("name") or "",
            "longitude": float(location.get("longitude", 0.0)),
            "latitude": float(location.get("latitude", 0.0)),
            "address": location.get("address") or "",
        },
        "estimated_cost": {
            "amount": float(estimated_cost.get("amount", 0)),
            "currency": estimated_cost.get("currency", "CNY"),
        },
    }


def _state_headers(state: dict[str, Any]) -> dict[str, Any] | None:
    copilotkit = state.get("copilotkit")
    if not isinstance(copilotkit, dict):
        return None
    headers = copilotkit.get("headers")
    if isinstance(headers, dict):
        return headers
    return None


def _next_day_date(itinerary: dict[str, Any], day_plans: list[dict[str, Any]]) -> str:
    end_date = itinerary.get("end_date")
    if isinstance(end_date, str) and end_date:
        return _add_days(end_date, 1)
    if day_plans:
        last_date = day_plans[-1].get("date")
        if isinstance(last_date, str) and last_date:
            return _add_days(last_date, 1)
    start_date = itinerary.get("start_date")
    if isinstance(start_date, str) and start_date:
        return start_date
    return datetime.now().date().isoformat()


def _pick_center_coordinates(day_plans: list[dict[str, Any]]) -> tuple[float, float]:
    for dp in day_plans:
        for act in dp.get("activities", []):
            loc = act.get("location") or {}
            if loc.get("longitude") and loc.get("latitude"):
                return float(loc["longitude"]), float(loc["latitude"])
    return _FALLBACK_LONGITUDE, _FALLBACK_LATITUDE


def _recompute_summary(itinerary: dict[str, Any]) -> dict[str, Any]:
    """Recompute summary totals from the current day_plans."""
    day_plans: list[dict[str, Any]] = itinerary.get("day_plans") or []
    all_activities = [a for dp in day_plans for a in dp.get("activities", [])]
    total_cost = sum(
        float(a.get("estimated_cost", {}).get("amount", 0)) for a in all_activities
    )
    summary: dict[str, Any] = dict(itinerary.get("summary") or {})
    summary["total_activities"] = len(all_activities)
    summary["total_estimated_cost"] = round(total_cost)
    return {**itinerary, "summary": summary}


def _ok(tool_call_id: str, message: str) -> Command:  # type: ignore[type-arg]
    """Return a Command that only adds a ToolMessage (no state change)."""
    return Command(
        update={"messages": [ToolMessage(content=message, tool_call_id=tool_call_id)]}
    )


def _update(
    tool_call_id: str,
    message: str,
    new_itinerary: dict[str, Any],
    extra_update: dict[str, Any] | None = None,
) -> Command:  # type: ignore[type-arg]
    """Return a Command that updates itinerary + adds a ToolMessage.

    Refuses to write an itinerary without an ``id`` – that would mean the
    state was never properly initialised from the frontend and operating on it
    would silently wipe all existing data.
    """
    if not new_itinerary.get("id"):
        return _ok(
            tool_call_id,
            "⚠️ Cannot apply change: itinerary has no ID "
            "(frontend state not yet synced – please retry).",
        )
    update: dict[str, Any] = {
        "itinerary": _recompute_summary(new_itinerary),
        "messages": [ToolMessage(content=message, tool_call_id=tool_call_id)],
    }
    if extra_update is not None:
        update.update(extra_update)
    return Command(update=update)


# ── Inline tools ───────────────────────────────────────────────────────────


@tool
def update_itinerary_day(
    day: int,
    activities: list[dict[str, Any]],
    tool_call_id: Annotated[str, InjectedToolCallId],
    state: Annotated[dict[str, Any], InjectedState],
) -> Command:  # type: ignore[type-arg]
    """Replace ALL activities for a specific day with a new list.

    Arguments:
        day: The day number to update (1-indexed)
        activities: List of activities to replace the existing activities for the day
        tool_call_id: The ID of the tool call
        state: The state of the itinerary

    Returns:
    Command that updates the itinerary with the new activities

    Use this ONLY when regenerating an entire day's schedule.
    Do NOT use this to add a single activity — use add_activity instead.
    Only the specified day is modified; all other days remain unchanged.
    """
    itinerary: dict[str, Any] = dict(state.get("itinerary") or {})
    day_plans: list[dict[str, Any]] = list(itinerary.get("day_plans") or [])

    cleaned_activities = [
        {
            **a,
            "id": a.get("id") or f"activity-{uuid.uuid4().hex[:8]}",
            "estimated_cost": {
                "amount": float((a.get("estimated_cost") or {}).get("amount", 0)),
                "currency": (a.get("estimated_cost") or {}).get("currency", "CNY"),
            },
        }
        for a in activities
    ]

    updated_plans = [
        (
            {**dp, "activities": cleaned_activities}
            if dp.get("day_number") == day
            else dp
        )
        for dp in day_plans
    ]
    new_itinerary = {**itinerary, "day_plans": updated_plans}
    return _update(
        tool_call_id,
        f"Day {day} activities replaced ({len(cleaned_activities)} activities).",
        new_itinerary,
    )


@tool
def add_activity(
    day: int,
    activity: dict[str, Any],
    tool_call_id: Annotated[str, InjectedToolCallId],
    state: Annotated[dict[str, Any], InjectedState],
) -> Command:  # type: ignore[type-arg]
    """Add a SINGLE new activity to a specific day without replacing existing ones.

    Arguments:
        day: The day number to add the activity to (1-indexed)
        activity: The activity to add to the day
        tool_call_id: The ID of the tool call
        state: The state of the itinerary

    Returns:
    Command that updates the itinerary with the new activity

    Use this whenever the user asks to add or insert one activity.
    The activity object must strictly follow the Activity schema.
    Only the specified day is affected.
    """
    itinerary: dict[str, Any] = dict(state.get("itinerary") or {})
    day_plans: list[dict[str, Any]] = list(itinerary.get("day_plans") or [])

    act = {
        **activity,
        "id": activity.get("id") or f"activity-{uuid.uuid4().hex[:8]}",
        "estimated_cost": {
            "amount": float((activity.get("estimated_cost") or {}).get("amount", 0)),
            "currency": (activity.get("estimated_cost") or {}).get("currency", "CNY"),
        },
    }
    updated_plans = [
        (
            {**dp, "activities": [*dp.get("activities", []), act]}
            if dp.get("day_number") == day
            else dp
        )
        for dp in day_plans
    ]
    new_itinerary = {**itinerary, "day_plans": updated_plans}
    return _update(
        tool_call_id,
        f'Added "{act.get("name", "activity")}" to day {day}.',
        new_itinerary,
    )


@tool
def remove_spot(
    day: int,
    spot_name: str,
    tool_call_id: Annotated[str, InjectedToolCallId],
    state: Annotated[dict[str, Any], InjectedState],
) -> Command:  # type: ignore[type-arg]
    """Remove a single spot/activity from a specific day by name.

    Arguments:
        day: The day number to remove the activity from (1-indexed)
        spot_name: The name of the activity to remove
        tool_call_id: The ID of the tool call
        state: The state of the itinerary

    Returns:
    Command that updates the itinerary with the removed activity

    Only that one activity is removed; all other activities and all other
    days remain unchanged.
    """
    itinerary: dict[str, Any] = dict(state.get("itinerary") or {})
    day_plans: list[dict[str, Any]] = list(itinerary.get("day_plans") or [])
    spot_lower = spot_name.lower()
    removed = False

    def _filter(dp: dict[str, Any]) -> dict[str, Any]:
        nonlocal removed
        if dp.get("day_number") != day:
            return dp
        original = dp.get("activities", [])
        kept = [
            a
            for a in original
            if not (
                a.get("name", "").lower() == spot_lower
                or spot_lower in a.get("name", "").lower()
                or a.get("name", "").lower() in spot_lower
            )
        ]
        if len(kept) < len(original):
            removed = True
        return {**dp, "activities": kept}

    updated_plans = [_filter(dp) for dp in day_plans]
    new_itinerary = {**itinerary, "day_plans": updated_plans}
    msg = (
        f'Removed "{spot_name}" from day {day}.'
        if removed
        else f'"{spot_name}" not found in day {day}; no change.'
    )
    return _update(tool_call_id, msg, new_itinerary)


@tool
def delete_day(
    day: int,
    tool_call_id: Annotated[str, InjectedToolCallId],
    state: Annotated[dict[str, Any], InjectedState],
) -> Command:  # type: ignore[type-arg]
    """Completely remove a day from the itinerary.

    Arguments:
        day: The day number to delete (1-indexed)
        tool_call_id: The ID of the tool call
        state: The state of the itinerary

    Returns:
    Command that updates the itinerary with the deleted day

    All activities for that day are deleted.  Remaining days are
    renumbered (1, 2, 3 …) and their dates are shifted forward to remain
    consecutive (e.g. deleting day 2 moves former day 3 to day 2's date).
    """
    itinerary: dict[str, Any] = dict(state.get("itinerary") or {})
    day_plans: list[dict[str, Any]] = list(itinerary.get("day_plans") or [])

    filtered = [dp for dp in day_plans if dp.get("day_number") != day]
    if not filtered:
        return _ok(tool_call_id, f"Day {day} deleted; itinerary is now empty.")

    first_date: str = filtered[0]["date"]
    renumbered = [
        {**dp, "day_number": i + 1, "date": _add_days(first_date, i)}
        for i, dp in enumerate(filtered)
    ]
    new_itinerary = {
        **itinerary,
        "day_plans": renumbered,
        "start_date": renumbered[0]["date"],
        "end_date": renumbered[-1]["date"],
    }
    return _update(
        tool_call_id,
        (
            f"Day {day} deleted; {len(renumbered)} remaining day(s) "
            "renumbered with consecutive dates."
        ),
        new_itinerary,
    )


@tool
def add_day(
    date: str,
    activities: list[dict[str, Any]],
    notes: str,
    tool_call_id: Annotated[str, InjectedToolCallId],
    state: Annotated[dict[str, Any], InjectedState],
) -> Command:  # type: ignore[type-arg]
    """Apply a pre-planned new day and append it to itinerary.

    Arguments:
        date: Reserved compatibility arg. Actual date comes from pending plan.
        activities: Reserved compatibility arg. Actual activities come from pending plan.
        notes: Reserved compatibility arg. Notes come from pending plan when available.
        tool_call_id: The ID of the tool call
        state: The state of the itinerary

    Returns:
    Command that updates the itinerary with the new day.
    """
    itinerary: dict[str, Any] = dict(state.get("itinerary") or {})
    day_plans: list[dict[str, Any]] = list(itinerary.get("day_plans") or [])
    headers = _state_headers(state)
    with tool_span(
        "add_day",
        headers=headers,
        attributes={"trip.day.existing_count": len(day_plans)},
    ) as span:
        start = perf_counter()
        pending_plan = state.get("pending_day_plan")
        if not isinstance(pending_plan, dict):
            span.set_attribute("tool.outcome", "rejected")
            span.set_attribute("tool.fallback_reason", "missing_pending_day_plan")
            span.set_attribute("tool.latency_ms", round((perf_counter() - start) * 1000, 2))
            return _ok(
                tool_call_id,
                "⚠️ 新增一天前请先调用 plan_new_day 进行规划，然后再调用 add_day。",
            )

        pending_date = pending_plan.get("date")
        pending_activities = pending_plan.get("activities")
        pending_notes = pending_plan.get("notes")
        if (
            not isinstance(pending_date, str)
            or pending_date.strip() == ""
            or not isinstance(pending_activities, list)
            or len(pending_activities) == 0
        ):
            span.set_attribute("tool.outcome", "rejected")
            span.set_attribute("tool.fallback_reason", "invalid_pending_day_plan")
            span.set_attribute("tool.latency_ms", round((perf_counter() - start) * 1000, 2))
            return Command(
                update={
                    "pending_day_plan": None,
                    "messages": [
                        ToolMessage(
                            content=(
                                "⚠️ 规划结果不完整，已清理无效草案。"
                                "请重新调用 plan_new_day 后再执行 add_day。"
                            ),
                            tool_call_id=tool_call_id,
                        )
                    ],
                }
            )

        clean_notes = (
            pending_notes
            if isinstance(pending_notes, str)
            and pending_notes not in ("", "undefined", "null")
            else ""
        )
        normalized_activities = [
            _normalize_activity(a) for a in pending_activities if isinstance(a, dict)
        ]
        if not normalized_activities:
            span.set_attribute("tool.outcome", "rejected")
            span.set_attribute("tool.fallback_reason", "empty_pending_activities")
            span.set_attribute("tool.latency_ms", round((perf_counter() - start) * 1000, 2))
            return Command(
                update={
                    "pending_day_plan": None,
                    "messages": [
                        ToolMessage(
                            content=(
                                "⚠️ 规划草案没有可写入的活动。"
                                "请重新调用 plan_new_day 生成活动后再执行 add_day。"
                            ),
                            tool_call_id=tool_call_id,
                        )
                    ],
                }
            )

        new_day_number = len(day_plans) + 1
        new_day: dict[str, Any] = {
            "day_number": new_day_number,
            "date": pending_date,
            "activities": normalized_activities,
            "notes": clean_notes,
        }
        new_itinerary = {
            **itinerary,
            "day_plans": [*day_plans, new_day],
            "end_date": pending_date,
        }
        if not new_itinerary.get("start_date"):
            new_itinerary["start_date"] = pending_date

        span.set_attribute("tool.outcome", "ok")
        span.set_attribute("trip.day.target_day_number", new_day_number)
        span.set_attribute("trip.plan.activity_count", len(normalized_activities))
        span.set_attribute("tool.latency_ms", round((perf_counter() - start) * 1000, 2))
        return _update(
            tool_call_id,
            f"Day {new_day_number} ({pending_date}) added from planned draft.",
            new_itinerary,
            extra_update={"pending_day_plan": None},
        )


@tool
def update_markdown(
    markdown: str,
    tool_call_id: Annotated[str, InjectedToolCallId],
) -> Command:  # type: ignore[type-arg]
    """Update the Markdown travel narrative displayed in the itinerary viewer.

    Arguments:
        markdown: The Markdown content to update
        tool_call_id: The ID of the tool call

    Returns:
    Command that updates the itinerary with the new Markdown content

    Call this after significant itinerary changes to keep the narrative in
    sync with the structured data.
    """
    return Command(
        update={
            "markdown_content": markdown,
            "messages": [
                ToolMessage(
                    content="Markdown narrative updated.", tool_call_id=tool_call_id
                )
            ],
        }
    )


# ── Async factory tool (needs Nacos for attraction lookup) ─────────────────


def make_plan_new_day_tool(nacos_naming: NacosNaming) -> Any:
    """Return an async 'plan_new_day' tool that proposes one new day plan."""

    class _PlanActivity(BaseModel):
        name: str = Field(description="Activity / attraction / hotel name")
        description: str = Field(description="Short description (<= 40 chars)")
        category: str = Field(
            description="sightseeing|cultural|shopping|dining|entertainment|transportation|nature"
        )
        start_time: str = Field(description="HH:MM")
        end_time: str = Field(description="HH:MM")
        estimated_cost: float = Field(description="Estimated cost in CNY")
        longitude: float = Field(description="Longitude")
        latitude: float = Field(description="Latitude")
        address: str = Field(description="Full address")
        kind: str = Field(
            default="attraction_visit",
            description="attraction_visit|hotel_stay|transport|custom",
        )

    class _PlanResult(BaseModel):
        activities: list[_PlanActivity] = Field(description="Planned activities")
        notes: str = Field(default="", description="Optional day notes")

    @tool("plan_new_day")
    async def plan_new_day(
        preference: str,
        notes: str,
        target_date: str | None,
        tool_call_id: Annotated[str, InjectedToolCallId],
        state: Annotated[dict[str, Any], InjectedState],
    ) -> Command:  # type: ignore[type-arg]
        """Plan one new day draft from realtime candidates without persisting."""
        itinerary: dict[str, Any] = dict(state.get("itinerary") or {})
        day_plans: list[dict[str, Any]] = list(itinerary.get("day_plans") or [])
        headers = _state_headers(state)
        destination = str(itinerary.get("destination") or "").strip()
        day_number = len(day_plans) + 1
        date = target_date or _next_day_date(itinerary, day_plans)
        fallback_reasons: list[str] = []

        with tool_span(
            "plan_new_day",
            headers=headers,
            attributes={
                "trip.day.target_day_number": day_number,
                "trip.day.has_target_date": bool(target_date),
                "itinerary.destination": destination,
            },
        ) as span:
            start = perf_counter()

            if not destination:
                span.set_attribute("tool.outcome", "rejected")
                span.set_attribute("tool.fallback_reason", "missing_destination")
                span.set_attribute("tool.latency_ms", round((perf_counter() - start) * 1000, 2))
                return _ok(
                    tool_call_id,
                    "⚠️ 当前行程缺少目的地信息，请先确认行程后再新增一天。",
                )

            if target_date:
                try:
                    datetime.fromisoformat(target_date)
                except ValueError:
                    span.set_attribute("tool.outcome", "rejected")
                    span.set_attribute("tool.fallback_reason", "invalid_target_date")
                    span.set_attribute(
                        "tool.latency_ms", round((perf_counter() - start) * 1000, 2)
                    )
                    return _ok(
                        tool_call_id,
                        "⚠️ target_date 需要是 YYYY-MM-DD 格式，请修正后重试。",
                    )

            center_lon, center_lat = _pick_center_coordinates(day_plans)
            attraction_candidates = []
            hotel_candidates = []
            attraction_map: dict[str, Any] = {}
            hotel_map: dict[str, Any] = {}

            try:
                attraction_result = await search_attractions_nearby(
                    nacos_naming=nacos_naming,
                    center_longitude=center_lon,
                    center_latitude=center_lat,
                    radius_km=25.0,
                    limit=20,
                )
                attraction_candidates = attraction_result.attractions
                attraction_map = {a.name: a for a in attraction_candidates}
            except Exception as exc:
                logger.warning("plan_new_day attraction search failed: %s", exc)
                span.record_exception(exc)
                fallback_reasons.append("attraction_service_error")

            try:
                hotel_result = await search_hotels_nearby(
                    nacos_naming=nacos_naming,
                    center_longitude=center_lon,
                    center_latitude=center_lat,
                    radius_km=8.0,
                    limit=6,
                )
                hotel_candidates = hotel_result.hotels
                hotel_map = {h.name: h for h in hotel_candidates}
            except Exception as exc:
                logger.warning("plan_new_day hotel search failed: %s", exc)
                span.record_exception(exc)
                fallback_reasons.append("hotel_service_error")

            attractions_text = (
                "\n".join(
                    f"- {a.name}（{a.description}，lat={a.latitude:.4f}, lon={a.longitude:.4f}）"
                    for a in attraction_candidates
                )
                or f"暂无实时景点候选，请根据 {destination} 合理生成。"
            )
            hotels_text = (
                "\n".join(
                    f"- {h.name}（{h.address}，lat={h.latitude:.4f}, lon={h.longitude:.4f}）"
                    for h in hotel_candidates
                )
                or f"暂无实时酒店候选，可不安排酒店活动。"
            )

            from langchain_openai import ChatOpenAI  # local import to avoid circular deps

            settings = get_settings()
            chat_model = ChatOpenAI(
                model="gpt-4o-mini",
                temperature=0.4,
                api_key=settings.openai.api_key,
                base_url=settings.openai.base_url,
            )

            prompt = (
                f"为{destination}行程新增第{day_number}天（日期 {date}）。\n"
                f"用户偏好：{preference or '未指定'}\n"
                f"补充备注：{notes or '无'}\n\n"
                f"景点候选：\n{attractions_text}\n\n"
                f"酒店候选：\n{hotels_text}\n\n"
                "请生成 3-4 个活动，时间不得重叠，全部在目的地城市范围。"
                "优先复用候选中的真实名称与坐标。"
            )

            planned_activities: list[dict[str, Any]] = []
            planned_notes = notes
            try:
                structured_llm = chat_model.with_structured_output(_PlanResult)
                result: _PlanResult = _PlanResult.model_validate(
                    await structured_llm.ainvoke(prompt)
                )
                planned_notes = result.notes or notes
                for act in result.activities:
                    matched_attraction = attraction_map.get(act.name)
                    matched_hotel = hotel_map.get(act.name)
                    kind = act.kind
                    if matched_hotel and kind == "attraction_visit":
                        kind = "hotel_stay"

                    planned_activities.append(
                        _normalize_activity(
                            {
                                "name": act.name,
                                "description": act.description,
                                "start_time": act.start_time,
                                "end_time": act.end_time,
                                "category": act.category,
                                "kind": kind,
                                "location": {
                                    "name": act.name,
                                    "longitude": (
                                        matched_attraction.longitude
                                        if matched_attraction
                                        else matched_hotel.longitude
                                        if matched_hotel
                                        else act.longitude
                                    ),
                                    "latitude": (
                                        matched_attraction.latitude
                                        if matched_attraction
                                        else matched_hotel.latitude
                                        if matched_hotel
                                        else act.latitude
                                    ),
                                    "address": (
                                        matched_attraction.address
                                        if matched_attraction
                                        else matched_hotel.address
                                        if matched_hotel
                                        else act.address
                                    ),
                                },
                                "estimated_cost": {
                                    "amount": act.estimated_cost,
                                    "currency": "CNY",
                                },
                                "attraction_id": (
                                    matched_attraction.id if matched_attraction else None
                                ),
                                "hotel_id": matched_hotel.id if matched_hotel else None,
                            }
                        )
                    )
            except Exception as exc:
                logger.error("plan_new_day LLM planning failed: %s", exc)
                span.record_exception(exc)
                fallback_reasons.append("llm_plan_error")

            if not planned_activities and attraction_candidates:
                fallback_reasons.append("template_from_attractions")
                slot_pairs = [("09:00", "11:00"), ("12:00", "13:30"), ("14:30", "17:00")]
                for idx, attraction in enumerate(attraction_candidates[:3]):
                    start_time, end_time = slot_pairs[idx]
                    planned_activities.append(
                        _normalize_activity(
                            {
                                "name": attraction.name,
                                "description": attraction.description,
                                "start_time": start_time,
                                "end_time": end_time,
                                "category": "sightseeing",
                                "kind": "attraction_visit",
                                "location": {
                                    "name": attraction.name,
                                    "longitude": attraction.longitude,
                                    "latitude": attraction.latitude,
                                    "address": attraction.address,
                                },
                                "estimated_cost": {"amount": 80, "currency": "CNY"},
                                "attraction_id": attraction.id,
                                "hotel_id": None,
                            }
                        )
                    )

            if not planned_activities:
                span.set_attribute("tool.outcome", "fallback")
                span.set_attribute("tool.fallback_reason", "no_plan_candidates")
                span.set_attribute("tool.latency_ms", round((perf_counter() - start) * 1000, 2))
                return _ok(
                    tool_call_id,
                    "⚠️ 暂时无法生成可执行的一天安排，请补充偏好（例如美食/亲子/轻松）后重试。",
                )

            outcome = "fallback" if fallback_reasons else "ok"
            span.set_attribute("tool.outcome", outcome)
            span.set_attribute("trip.search.attraction_count", len(attraction_candidates))
            span.set_attribute("trip.search.hotel_count", len(hotel_candidates))
            span.set_attribute("trip.plan.activity_count", len(planned_activities))
            if fallback_reasons:
                span.set_attribute("tool.fallback_reason", ",".join(sorted(set(fallback_reasons))))
            span.set_attribute("tool.latency_ms", round((perf_counter() - start) * 1000, 2))

            pending_day_plan = {
                "date": date,
                "activities": planned_activities,
                "notes": planned_notes,
                "source": "plan_new_day",
            }
            return Command(
                update={
                    "pending_day_plan": pending_day_plan,
                    "messages": [
                        ToolMessage(
                            content=(
                                f"已完成第 {day_number} 天草案（{date}，"
                                f"{len(planned_activities)} 个活动）。请调用 add_day 写入。"
                            ),
                            tool_call_id=tool_call_id,
                        )
                    ],
                }
            )

    return plan_new_day


def make_regenerate_day_tool(nacos_naming: NacosNaming) -> Any:
    """Return an async 'regenerate_day' tool that
    uses Nacos to find fresh attractions.
    """

    class _RegActivity(BaseModel):
        name: str = Field(description="Activity / attraction name")
        description: str = Field(description="Short description (≤ 40 chars)")
        category: str = Field(
            description="sightseeing|cultural|shopping|dining|entertainment|transportation|nature"
        )
        start_time: str = Field(description="HH:MM")
        end_time: str = Field(description="HH:MM")
        estimated_cost: float = Field(description="Estimated cost in CNY")
        longitude: float = Field(description="Actual longitude of the location")
        latitude: float = Field(description="Actual latitude of the location")
        address: str = Field(description="Full address")

    class _RegResult(BaseModel):
        activities: list[_RegActivity] = Field(
            description="Regenerated activities for the day"
        )

    @tool("regenerate_day")
    async def regenerate_day(
        day: int,
        preference: str,
        tool_call_id: Annotated[str, InjectedToolCallId],
        state: Annotated[dict[str, Any], InjectedState],
    ) -> Command:  # type: ignore[type-arg]
        """Completely regenerate all activities for
        a specific day using real attractions.

        Arguments:
            day: The day number to regenerate (1-indexed)
            preference: The preference/style of the itinerary
            tool_call_id: The ID of the tool call
            state: The state of the itinerary

        Returns:
        Command that updates the itinerary with the regenerated day

        Queries the attraction service for the destination, then uses the LLM
        to create a fresh schedule tailored to the given preference/style.
        Only the specified day is replaced; all other days are unchanged.
        """
        itinerary: dict[str, Any] = dict(state.get("itinerary") or {})
        day_plans: list[dict[str, Any]] = list(itinerary.get("day_plans") or [])
        target = next((dp for dp in day_plans if dp.get("day_number") == day), None)
        if target is None:
            return _ok(tool_call_id, f"Day {day} not found in itinerary.")

        destination: str = itinerary.get("destination", "")

        # Pick destination coords from first activity with valid coordinates
        dest_lon = _FALLBACK_LONGITUDE
        dest_lat = _FALLBACK_LATITUDE
        for dp in day_plans:
            for act in dp.get("activities", []):
                loc = act.get("location") or {}
                if loc.get("longitude") and loc.get("latitude"):
                    dest_lon = float(loc["longitude"])
                    dest_lat = float(loc["latitude"])
                    break
            else:
                continue
            break

        # Search for fresh attractions
        try:
            search_result = await search_attractions_nearby(
                nacos_naming=nacos_naming,
                center_longitude=dest_lon,
                center_latitude=dest_lat,
                radius_km=25.0,
                limit=20,
            )
            attractions_text = "\n".join(
                (
                    f"- {a.name}: {a.description} "
                    f"(lat={a.latitude:.4f}, lon={a.longitude:.4f}, tags={a.tags})"
                )
                for a in search_result.attractions
            )
            attraction_map = {a.name: a for a in search_result.attractions}
        except Exception as exc:
            logger.warning("Attraction search failed for regenerate_day: %s", exc)
            attractions_text = f"General attractions in {destination}"
            attraction_map = {}

        # Ask LLM to regenerate the day
        from langchain_openai import ChatOpenAI  # local import to avoid circular deps

        settings = get_settings()
        chat_model = ChatOpenAI(
            model="gpt-4o-mini",
            temperature=0.6,
            api_key=settings.openai.api_key,
            base_url=settings.openai.base_url,
        )

        prompt = (
            f"Regenerate day {day} of a trip to {destination}.\n"
            f"Date: {target.get('date', '')}\n"
            f"User preference / style: {preference}\n\n"
            f"Available attractions:\n{attractions_text}\n\n"
            f"Generate 3–4 varied activities. "
            f"When available, use the EXACT name and coordinates from the list above. "
            f"Keep all activities within {destination}."
        )

        try:
            structured_llm = chat_model.with_structured_output(_RegResult)
            result: _RegResult = _RegResult.model_validate(
                await structured_llm.ainvoke(prompt)
            )

            new_activities: list[dict[str, Any]] = []
            for act in result.activities:
                # Try to enrich with exact coordinates from attraction service
                matched = attraction_map.get(act.name)
                lon = matched.longitude if matched else act.longitude
                lat = matched.latitude if matched else act.latitude
                addr = matched.address if matched else act.address
                attraction_id = matched.id if matched else None
                new_activities.append(
                    {
                        "id": f"activity-{uuid.uuid4().hex[:8]}",
                        "name": act.name,
                        "description": act.description,
                        "start_time": act.start_time,
                        "end_time": act.end_time,
                        "category": act.category,
                        "location": {
                            "name": act.name,
                            "longitude": lon,
                            "latitude": lat,
                            "address": addr,
                        },
                        "estimated_cost": {
                            "amount": act.estimated_cost,
                            "currency": "CNY",
                        },
                        "kind": "attraction_visit",
                        "attraction_id": attraction_id,
                        "hotel_id": None,
                    }
                )
        except Exception as exc:
            logger.error("LLM regeneration failed: %s", exc)
            return _ok(tool_call_id, f"Failed to regenerate day {day}: {exc}")

        updated_plans = [
            (
                {**dp, "activities": new_activities}
                if dp.get("day_number") == day
                else dp
            )
            for dp in day_plans
        ]
        new_itinerary = {**itinerary, "day_plans": updated_plans}
        return _update(
            tool_call_id,
            (
                f"Day {day} regenerated with {len(new_activities)} "
                f'new activities (preference: "{preference}").'
            ),
            new_itinerary,
        )

    return regenerate_day


# ── Public convenience: all tools except the factory one ──────────────────

# Tools that need no Nacos; regenerate_day/plan_new_day are added via
# make_*_tool(nacos_naming) when Nacos is enabled.
INLINE_TOOLS = [
    update_itinerary_day,
    add_activity,
    remove_spot,
    delete_day,
    add_day,
    update_markdown,
]
