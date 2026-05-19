"""TripSphere Locust 压测脚本 — 三链路统一入口

CHAIN=a  链路 A：POST /api/v1/itineraries/plannings（REST 规划链）
CHAIN=b  链路 B：POST / AG-UI ReAct 对话（trip-itinerary-planner 聊天 Agent）
CHAIN=c  链路 C：POST / AG-UI ADK 跨服务（trip-chat-service → order_assistant）

环境变量（也可通过 run-matrix.sh 传入）：
  CHAIN           a | b | c，默认 a
  TARGET_HOST     HTTP 地址（每条链路有独立默认值）
  EXPERIMENT_ID   实验主键，写入 x-experiment-id 头
  FAULT_SCENARIO  fault_scenarios.yaml 中的场景名，空字符串 = baseline
  SCENARIO_MODE   single | combo，默认 single
  USER_ID         x-user-id，默认 42
  PAYLOAD_MODE    fixed | mixed（仅链路 A），默认 fixed
  MATRIX_CELL     四象限标签（baseline-low / fault-low / …）
  ARTIFACT_DIR    输出目录，默认 artifacts/locust
  SEED            随机种子，默认 42（链路 A 目的地采样）
  CHAIN_B_ITINERARY_ID  预置行程 ID（不填则用占位 ID，工具调用可能返回 404）
  CHAIN_B_DESTINATION   行程目的地，默认 Shanghai

故障命中说明：
  x-fault-scenario DSL 由 Locust 透传给服务端 FaultRegistry 独立抽样。
  实际命中次数请通过 Tempo TraceQL 查询 fault.injected=true，
  quality.csv 只记录"声明的 DSL 和请求结果"。
"""

import csv
import itertools
import json
import os
import random
import time
import uuid
from collections import Counter
from pathlib import Path
from typing import Any

import yaml
from locust import HttpUser, between, events

# ── 公共配置 ─────────────────────────────────────────────────────────────────────

_SCRIPT_DIR = Path(__file__).parent
_SCENARIOS_FILE = _SCRIPT_DIR / "fault_scenarios.yaml"

CHAIN = os.environ.get("CHAIN", "a").lower().strip()

_CHAIN_DEFAULT_HOSTS: dict[str, str] = {
    "a": "http://localhost:24215",
    "b": "http://localhost:24215",
    "c": "http://localhost:24210",
}
TARGET_HOST = os.environ.get("TARGET_HOST", _CHAIN_DEFAULT_HOSTS.get(CHAIN, "http://localhost:24215"))

EXPERIMENT_ID = os.environ.get("EXPERIMENT_ID", f"locust-{int(time.time())}")
FAULT_SCENARIO = os.environ.get("FAULT_SCENARIO", "")
SCENARIO_MODE = os.environ.get("SCENARIO_MODE", "single")
USER_ID = os.environ.get("USER_ID", "42")
PAYLOAD_MODE = os.environ.get("PAYLOAD_MODE", "fixed")
MATRIX_CELL = os.environ.get("MATRIX_CELL", "baseline-low")
ARTIFACT_DIR = Path(os.environ.get("ARTIFACT_DIR", "artifacts/locust")) / EXPERIMENT_ID
SEED = int(os.environ.get("SEED", "42"))

CHAIN_B_ITINERARY_ID = os.environ.get("CHAIN_B_ITINERARY_ID", "locust-placeholder-itin")
CHAIN_B_DESTINATION = os.environ.get("CHAIN_B_DESTINATION", "Shanghai")

# 支持逗号分隔的 ID 池，每个用户轮流绑定不同行程，避免并发写冲突
_CHAIN_B_ID_POOL: list[str] = [
    iid.strip() for iid in CHAIN_B_ITINERARY_ID.split(",") if iid.strip()
] or ["locust-placeholder-itin"]
_chain_b_id_counter = itertools.count()

# 链路 A 降级阈值
DEGRADE_MIN_DAY_PLANS = int(os.environ.get("DEGRADE_MIN_DAY_PLANS", "1"))
DEGRADE_MIN_ACTIVITIES = int(os.environ.get("DEGRADE_MIN_ACTIVITIES", "1"))
DEGRADE_MIN_ACTIVITIES_PER_DAY = float(os.environ.get("DEGRADE_MIN_ACTIVITIES_PER_DAY", "2.0"))
DEGRADE_MIN_MARKDOWN_LEN = int(os.environ.get("DEGRADE_MIN_MARKDOWN_LEN", "200"))

# 链路 C — DB ground-truth for order_submit verification
_PG_HOST     = os.environ.get("PG_HOST", "localhost")
_PG_PORT     = int(os.environ.get("PG_PORT", "5432"))
_PG_DB       = os.environ.get("PG_DB", "order_db")
_PG_USER     = os.environ.get("PG_USER", "postgres")
_PG_PASSWORD = os.environ.get("PG_PASSWORD", "fudanse")

_DESTINATIONS = ["Shanghai", "Beijing", "Chengdu", "Hangzhou", "Xian"]
_INTERESTS_POOL = [
    ["culture"],
    ["food"],
    ["nature"],
    ["history", "culture"],
    ["food", "nature"],
]
_rng = random.Random(SEED)


# ── DSL 构建 ─────────────────────────────────────────────────────────────────────

def _build_fault_dsl(scenarios_file: Path, scenario_name: str, mode: str) -> str:
    """从 fault_scenarios.yaml 读取场景，拼接 x-fault-scenario DSL。"""
    if not scenario_name:
        return ""
    if not scenarios_file.exists():
        raise FileNotFoundError(f"场景文件不存在: {scenarios_file}")
    with scenarios_file.open(encoding="utf-8") as f:
        cfg = yaml.safe_load(f)
    group = cfg.get(mode) or {}
    entry = group.get(scenario_name)
    if entry is None:
        raise ValueError(
            f"场景 '{scenario_name}' 在 mode='{mode}' 下不存在，"
            f"可用场景: {list(group.keys())}"
        )
    parts: list[str] = []
    for fault in entry["faults"]:
        segment = f"{fault['target']}.{fault['primitive']}={fault['value']}"
        if "message" in fault:
            segment += f",message={fault['message']}"
        prob = fault.get("probability")
        if prob is not None and prob != 1.0:
            segment += f",probability={prob}"
        parts.append(segment)
    return ";".join(parts)


_FAULT_DSL: str = _build_fault_dsl(_SCENARIOS_FILE, FAULT_SCENARIO, SCENARIO_MODE)


# ── 公共 HTTP 头 ──────────────────────────────────────────────────────────────────

def _base_headers(request_id: str) -> dict[str, str]:
    headers: dict[str, str] = {
        "Content-Type": "application/json",
        "x-user-id": USER_ID,
        "x-experiment-id": EXPERIMENT_ID,
        "x-request-id": request_id,
    }
    if _FAULT_DSL:
        headers["x-fault-scenario"] = _FAULT_DSL
    return headers


# ── CSV 超集字段 ──────────────────────────────────────────────────────────────────

_CSV_FIELDS = [
    "ts",
    "request_id",
    "experiment_id",
    "matrix_cell",
    "chain",
    "fault_scenario_name",
    "scenario_mode",
    "fault_dsl",
    "status_code",
    "elapsed_ms",
    # 链路 A
    "itinerary_id",
    "day_plan_count",
    "activity_count",
    "markdown_length",
    "has_id",
    # 链路 B / C 共用 turn_type
    "turn_type",
    # 链路 B
    "text_chars",
    "tool_calls_count",
    "state_snapshot_seen",
    "run_error_seen",
    # 链路 C
    "order_draft_seen",
    "order_submit_seen",
    "remote_agent_delegated",
    # 通用
    "degraded",
    "degradation_signals",
    "error",
]

_quality_file: Any = None
_quality_writer: Any = None
_degradation_counts: dict[str, int] = {}
_total_success = 0
_total_degraded = 0


@events.init.add_listener
def _on_init(environment: Any, **_: Any) -> None:
    global _quality_file, _quality_writer
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    csv_path = ARTIFACT_DIR / "quality.csv"
    _quality_file = csv_path.open("w", newline="", encoding="utf-8")
    _quality_writer = csv.DictWriter(_quality_file, fieldnames=_CSV_FIELDS, extrasaction="ignore")
    _quality_writer.writeheader()
    _quality_file.flush()

    print(f"\n[TripSphere Locust]")
    print(f"  chain         : {CHAIN}")
    print(f"  experiment_id : {EXPERIMENT_ID}")
    print(f"  matrix_cell   : {MATRIX_CELL}")
    print(f"  fault_scenario: {FAULT_SCENARIO or '<baseline>'}")
    print(f"  scenario_mode : {SCENARIO_MODE}")
    print(f"  fault_dsl     : {_FAULT_DSL or '<none>'}")
    print(f"  target_host   : {TARGET_HOST}")
    print(f"  artifact_dir  : {ARTIFACT_DIR}")


@events.quitting.add_listener
def _on_quit(environment: Any, **_: Any) -> None:
    global _quality_file
    if _quality_file is not None:
        _quality_file.flush()
        _quality_file.close()
        _quality_file = None

    total = _total_success + _total_degraded
    rate = (_total_degraded / total * 100) if total > 0 else 0.0
    print(f"\n{'='*60}")
    print(f"[TripSphere Locust] Degradation Summary (chain={CHAIN})")
    print(f"  experiment_id  : {EXPERIMENT_ID}")
    print(f"  matrix_cell    : {MATRIX_CELL}")
    print(f"  fault_scenario : {FAULT_SCENARIO or '<baseline>'}")
    print(f"  total 2xx      : {total}")
    print(f"  degraded (2xx) : {_total_degraded}  ({rate:.1f}%)")
    if _degradation_counts:
        print(f"  signals breakdown:")
        for sig, cnt in sorted(_degradation_counts.items(), key=lambda x: -x[1]):
            print(f"    {sig:<25} {cnt}")
    print(f"  quality.csv    : {ARTIFACT_DIR / 'quality.csv'}")
    print(f"  run report     : python3 scripts/locust/generate_report.py {ARTIFACT_DIR / 'quality.csv'}")
    print(f"{'='*60}\n")


def _write_row(row: dict[str, Any]) -> None:
    global _total_success, _total_degraded
    if _quality_writer is None:
        return
    degraded = row.get("degraded", False)
    status_code = row.get("status_code", 0)
    if status_code in (200, 201):
        if degraded:
            _total_degraded += 1
            for sig in str(row.get("degradation_signals", "")).split(","):
                if sig:
                    _degradation_counts[sig] = _degradation_counts.get(sig, 0) + 1
        else:
            _total_success += 1
    _quality_writer.writerow(row)
    _quality_file.flush()


def _base_row(request_id: str, status_code: int, elapsed_ms: int, error: str = "") -> dict[str, Any]:
    return {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "request_id": request_id,
        "experiment_id": EXPERIMENT_ID,
        "matrix_cell": MATRIX_CELL,
        "chain": CHAIN,
        "fault_scenario_name": FAULT_SCENARIO,
        "scenario_mode": SCENARIO_MODE,
        "fault_dsl": _FAULT_DSL,
        "status_code": status_code,
        "elapsed_ms": elapsed_ms,
        "degraded": False,
        "degradation_signals": "",
        "error": error,
        "itinerary_id": "",
        "day_plan_count": 0,
        "activity_count": 0,
        "markdown_length": 0,
        "has_id": False,
        "turn_type": "",
        "text_chars": 0,
        "tool_calls_count": 0,
        "state_snapshot_seen": False,
        "run_error_seen": False,
        "order_draft_seen": False,
        "order_submit_seen": False,
        "remote_agent_delegated": False,
    }


# ── SSE 解析 ─────────────────────────────────────────────────────────────────────

def _parse_sse_events(body: bytes) -> list[dict[str, Any]]:
    """解析 SSE 响应体，返回所有 data: {...} 行解析后的事件列表。"""
    events_list: list[dict[str, Any]] = []
    try:
        text = body.decode("utf-8", errors="replace")
    except Exception:
        return events_list
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("data:"):
            payload = line[5:].strip()
            if payload:
                try:
                    events_list.append(json.loads(payload))
                except json.JSONDecodeError:
                    pass
    return events_list


# ── 链路 A：REST 规划链 ──────────────────────────────────────────────────────────

def _make_chain_a_payload() -> dict[str, Any]:
    if PAYLOAD_MODE == "fixed":
        return {
            "destination": "Shanghai",
            "start_date": "2026-05-01",
            "end_date": "2026-05-03",
            "interests": ["culture"],
            "pace": "moderate",
        }
    return {
        "destination": _rng.choice(_DESTINATIONS),
        "start_date": "2026-05-01",
        "end_date": "2026-05-03",
        "interests": _rng.choice(_INTERESTS_POOL),
        "pace": "moderate",
    }


def _parse_chain_a_quality(body: bytes, status_code: int) -> dict[str, Any]:
    empty: dict[str, Any] = {
        "itinerary_id": "",
        "day_plan_count": 0,
        "activity_count": 0,
        "markdown_length": 0,
        "has_id": False,
    }
    if status_code not in (200, 201) or not body:
        return empty
    try:
        data: dict[str, Any] = json.loads(body)
        itinerary = data.get("itinerary") or {}
        day_plans: list[Any] = itinerary.get("day_plans") or []
        activity_count = sum(len(day.get("activities") or []) for day in day_plans)
        markdown = data.get("markdown_content") or ""
        itin_id = itinerary.get("id", "")
        return {
            "itinerary_id": itin_id,
            "day_plan_count": len(day_plans),
            "activity_count": activity_count,
            "markdown_length": len(markdown),
            "has_id": bool(itin_id),
        }
    except Exception:
        return empty


# 硬编码 fallback 坐标（nodes.py geocoding 异常时的兜底值）
_DEFAULT_COORDS = (121.4737, 31.2304)

# 坐标去重精度：4 位小数 ≈ 11m，用于识别"完全相同坐标"
_COORD_DEDUP_DECIMALS = 4

# 当同一坐标点占所有 activity 坐标的比例超过此阈值时判定为 fallback
# 正常路径：每个 activity 匹配到不同 POI，重复率极低（≈0）
# fallback 路径：所有匹配失败的 activity 共用同一个 destination_coords，重复率接近 1
_DOMINANT_COORD_THRESHOLD = 0.5

# 非目标城市时检测"坐标是否堆在上海默认点附近"的范围（0.5° ≈ 55km）
_WRONG_CITY_PROXIMITY = 0.5


def _extract_activity_coords(data: dict[str, Any]) -> list[tuple[float, float]]:
    """提取所有 activity 的 (lon, lat)，跳过 (0, 0) 占位值。"""
    coords: list[tuple[float, float]] = []
    itinerary = data.get("itinerary") or {}
    for day in itinerary.get("day_plans") or []:
        for act in day.get("activities") or []:
            loc = act.get("location") or {}
            lon = float(loc.get("longitude") or 0.0)
            lat = float(loc.get("latitude") or 0.0)
            if lon != 0.0 or lat != 0.0:
                coords.append((lon, lat))
    return coords


def _detect_geocoding_fallback(coords: list[tuple[float, float]], destination: str) -> bool:
    """判断坐标是否呈现 fallback 特征。

    坐标分配逻辑（见 finalize_itinerary）：
    - 正常路径：LLM 生成的 activity 名称与 attraction gRPC 返回的 POI 模糊匹配成功
      → 每个 activity 拿到真实分散坐标，重复率接近 0。
    - 软 fallback：名称匹配失败（LLM 自由命名与 DB 景点名不符）
      → 所有匹配不上的 activity 共用同一个 destination_coords（geocoding 的城市中心点）
      → 产生大量完全相同的坐标对。
    - 硬 fallback：geocoding 工具本身抛异常
      → destination_coords 退化为硬编码 (121.4737, 31.2304)
      → 情况同上，且城市中心点本身也是固定值。

    检测策略：
    1. 主检测：统计出现次数最多的坐标点（圆整到 ~11m 精度）占总数的比例。
       若 ≥ 50%，说明大量 activity 共用同一坐标 → fallback 路径命中。
       （正常城市旅游景点分散在方圆数十公里，不会在同一个 11m 方格里堆积。）
    2. 辅检测：对非上海目的地，额外检查坐标是否意外堆在上海默认点附近
       （防止 geocoding 成功返回了上海坐标但目的地是其他城市）。
    """
    if not coords or len(coords) < 2:
        return False

    # 主检测：dominant coordinate ratio
    rounded = [
        (round(lon, _COORD_DEDUP_DECIMALS), round(lat, _COORD_DEDUP_DECIMALS))
        for lon, lat in coords
    ]
    most_common_count = Counter(rounded).most_common(1)[0][1]
    if most_common_count / len(coords) >= _DOMINANT_COORD_THRESHOLD:
        return True

    # 辅检测：非上海目的地但坐标堆在上海中心附近
    if "shanghai" not in destination.lower():
        near_default = sum(
            1 for lon, lat in coords
            if abs(lon - _DEFAULT_COORDS[0]) < _WRONG_CITY_PROXIMITY
            and abs(lat - _DEFAULT_COORDS[1]) < _WRONG_CITY_PROXIMITY
        )
        if near_default / len(coords) >= 0.8:
            return True

    return False


def _detect_chain_a_degradation(
    quality: dict[str, Any],
    status_code: int,
    raw_body: bytes,
    destination: str,
) -> tuple[bool, str]:
    if status_code not in (200, 201):
        return False, ""
    signals: list[str] = []
    try:
        data: dict[str, Any] = json.loads(raw_body) if raw_body else {}
        coords = _extract_activity_coords(data)
        if _detect_geocoding_fallback(coords, destination):
            signals.append("geocoding_fallback")
    except Exception:
        pass
    if quality["day_plan_count"] < DEGRADE_MIN_DAY_PLANS:
        signals.append("no_day_plans")
    else:
        if quality["activity_count"] < DEGRADE_MIN_ACTIVITIES:
            signals.append("no_activities")
        elif quality["day_plan_count"] > 0:
            avg_per_day = quality["activity_count"] / quality["day_plan_count"]
            if avg_per_day < DEGRADE_MIN_ACTIVITIES_PER_DAY:
                signals.append("sparse_activities")
    if quality["markdown_length"] < DEGRADE_MIN_MARKDOWN_LEN:
        signals.append("short_markdown")
    if not quality["has_id"]:
        signals.append("no_id")
    return bool(signals), ",".join(signals)


def _chain_a_task(self: "HttpUser") -> None:
    """链路 A 任务：POST /api/v1/itineraries/plannings"""
    payload = _make_chain_a_payload()
    destination: str = payload["destination"]
    request_id = str(uuid.uuid4())
    headers = _base_headers(request_id)
    t_start = time.monotonic()
    with self.client.post(
        "/api/v1/itineraries/plannings",
        json=payload,
        headers=headers,
        catch_response=True,
        name="/api/v1/itineraries/plannings",
    ) as resp:
        elapsed_ms = int((time.monotonic() - t_start) * 1000)
        quality = _parse_chain_a_quality(resp.content, resp.status_code)
        if resp.status_code in (200, 201):
            resp.success()
            error_text = ""
        else:
            error_text = (resp.text or "")[:300]
            resp.failure(f"HTTP {resp.status_code}")
        degraded, degradation_signals = _detect_chain_a_degradation(
            quality, resp.status_code, resp.content, destination
        )
        row = _base_row(request_id, resp.status_code, elapsed_ms, error_text)
        row.update(quality)
        row["degraded"] = degraded
        row["degradation_signals"] = degradation_signals
        _write_row(row)


# ── 链路 B：AG-UI ReAct 对话 ──────────────────────────────────────────────────────

# 固定对话剧本：每个 Locust 用户实例按顺序循环执行
_CHAIN_B_TURNS = [
    {
        "turn_type": "query_itinerary",
        "message": "请告诉我当前行程的目的地和天数安排。",
        "expects_tool": False,
    },
    {
        "turn_type": "plan_new_day",
        "message": "帮我新增一天的行程，安排一些文化类景点。",
        "expects_tool": True,
    },
    {
        "turn_type": "regenerate_day",
        "message": "请重新生成第1天的活动安排，偏向美食体验。",
        "expects_tool": True,
    },
]


def _make_chain_b_payload(thread_id: str, run_id: str, message: str, itinerary_id: str = CHAIN_B_ITINERARY_ID) -> dict[str, Any]:
    """构造 AG-UI RunAgentInput（camelCase）。

    state 中注入最小行程数据，让 chat agent 有上下文可用。
    工具调用（plan_new_day / regenerate_day）需要后端存在该行程，
    否则工具返回 404 — 仍是有效的降级信号。
    """
    return {
        "threadId": thread_id,
        "runId": run_id,
        "state": {
            "itinerary": {
                "id": itinerary_id,
                "destination": CHAIN_B_DESTINATION,
                "start_date": "2026-05-01",
                "end_date": "2026-05-03",
                "day_plans": [],
            },
            "copilotkit": {"actions": []},
            "markdown_content": "",
            "pending_day_plan": None,
        },
        "messages": [
            {
                "id": str(uuid.uuid4()),
                "role": "user",
                "content": message,
            }
        ],
        "tools": [],
        "context": [],
        "forwardedProps": {},
    }


def _parse_chain_b_sse(
    sse_events: list[dict[str, Any]],
    expects_tool: bool,
) -> tuple[bool, str, dict[str, Any]]:
    """分析链路 B SSE 事件流，返回 (degraded, signals, metrics)。"""
    text_chars = 0
    tool_calls_count = 0
    state_snapshot_seen = False
    run_error_seen = False

    for ev in sse_events:
        ev_type = ev.get("type", "")
        if ev_type in ("TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CHUNK"):
            text_chars += len(ev.get("delta", ""))
        elif ev_type == "TOOL_CALL_START":
            tool_calls_count += 1
        elif ev_type in ("STATE_SNAPSHOT", "STATE_DELTA"):
            state_snapshot_seen = True
        elif ev_type == "RUN_ERROR":
            run_error_seen = True

    signals: list[str] = []
    if run_error_seen:
        signals.append("llm_error")
    if expects_tool and tool_calls_count == 0 and not run_error_seen:
        signals.append("no_tool_calls")
    if not state_snapshot_seen and not run_error_seen:
        signals.append("no_state_update")
    if text_chars == 0 and not run_error_seen:
        signals.append("no_response_text")

    return bool(signals), ",".join(signals), {
        "text_chars": text_chars,
        "tool_calls_count": tool_calls_count,
        "state_snapshot_seen": state_snapshot_seen,
        "run_error_seen": run_error_seen,
    }


def _chain_b_task(self: "HttpUser") -> None:
    """链路 B 任务：POST / (AG-UI ReAct 对话 turn)

    每轮 cycle（len(_CHAIN_B_TURNS) 个 turn）开始时重置 thread_id。
    这防止 MemorySaver 无限累积对话历史：长历史会导致某条 turn 内工具
    调用崩溃后，孤儿 AIMessage(tool_calls) 滞留检查点，令该线程此后所有
    请求被 OpenAI API 以 HTTP 400 拒绝，造成 Baseline 虚高降级率。
    """
    turn_idx = self._b_turn_idx % len(_CHAIN_B_TURNS)  # type: ignore[attr-defined]
    if turn_idx == 0:
        self._b_thread_id = str(uuid.uuid4())  # type: ignore[attr-defined]
    turn = _CHAIN_B_TURNS[turn_idx]
    self._b_turn_idx += 1  # type: ignore[attr-defined]
    turn_type: str = turn["turn_type"]
    expects_tool: bool = turn["expects_tool"]  # type: ignore[assignment]
    message: str = turn["message"]

    run_id = str(uuid.uuid4())
    request_id = str(uuid.uuid4())
    payload = _make_chain_b_payload(self._b_thread_id, run_id, message, self._b_itinerary_id)  # type: ignore[attr-defined]
    headers = _base_headers(request_id)
    headers["Accept"] = "text/event-stream"

    t_start = time.monotonic()
    with self.client.post(
        "/",
        json=payload,
        headers=headers,
        catch_response=True,
        name="/ (chat-turn)",
    ) as resp:
        elapsed_ms = int((time.monotonic() - t_start) * 1000)
        if resp.status_code in (200, 201):
            resp.success()
            error_text = ""
        else:
            error_text = (resp.text or "")[:300]
            resp.failure(f"HTTP {resp.status_code}")

        sse_events_list = _parse_sse_events(resp.content)
        degraded, signals, metrics = _parse_chain_b_sse(sse_events_list, expects_tool)

        row = _base_row(request_id, resp.status_code, elapsed_ms, error_text)
        row["turn_type"] = turn_type
        row.update(metrics)
        row["degraded"] = degraded
        row["degradation_signals"] = signals
        _write_row(row)


# ── 链路 C：AG-UI ADK 跨服务 ─────────────────────────────────────────────────────

# 上海迪士尼成人票 SKU（019cfad0-7d84-7442-953d-e84a99e151c4）：
#   - 景点票单日无 end_date，避免酒店房型的 checkout 日期传参问题
#   - 库存充裕（126+/天，到 2026-07-14），可支持并发高压测试
#   - 单 turn 合并建单+添加+提交：A2A 子 agent 跨 turn 不保留草稿上下文
_CHAIN_C_SKU = "019cfad0-7d84-7442-953d-e84a99e151c4"
_CHAIN_C_TASKS_DEF = [
    {
        "task_type": "order_submit",
        "message": (
            f"我想预订1张上海迪士尼成人票，SKU id 是 {_CHAIN_C_SKU}，"
            "游览日期 2026-06-15。"
            "请帮我创建订单草稿、添加这张票，然后立即提交下单。我确认预订。"
        ),
        "expects_delegation": True,
    },
]

# ADK 通过 transfer_to_agent 工具委托给子 Agent
_ADK_TRANSFER_TOOL = "transfer_to_agent"
# order_assistant 相关文本信号（匹配 TOOL_CALL_ARGS 或响应文本）
_ORDER_DRAFT_SIGNALS = frozenset(["order_draft", "订单草稿"])
_ORDER_SUBMIT_SIGNALS = frozenset([
    "submit_order", "下单", "已提交", "订单已",
    "订单号", "待支付", "待付款", "pending_payment", "order_status_pending",
])


def _make_chain_c_payload(thread_id: str, run_id: str, message: str) -> dict[str, Any]:
    """构造 trip-chat-service AG-UI RunAgentInput。

    x-user-id / x-experiment-id / x-fault-scenario 从 HTTP 头传递；
    make_extract_headers 会把它们读取到 state["headers"] 中，
    供 ADK 回调和 A2A 元数据使用。
    """
    return {
        "threadId": thread_id,
        "runId": run_id,
        "state": {},
        "messages": [
            {
                "id": str(uuid.uuid4()),
                "role": "user",
                "content": message,
            }
        ],
        "tools": [],
        "context": [],
        "forwardedProps": {},
    }


def _db_order_submitted(user_id: str, after_epoch_s: int) -> bool | None:
    """Query order_db to confirm an order was persisted for this user within the request window.

    Returns True (order found), False (definitively not found), or None (DB
    unavailable — caller should fall back to keyword-based detection).

    Note: orders.source_session stores the ADK sub-agent session ID, which is
    NOT the same as the AG-UI threadId. We correlate by user_id + time window instead.
    orders.created_at is epoch seconds (Instant.now().getEpochSecond()).
    """
    try:
        import psycopg2
        conn = psycopg2.connect(
            host=_PG_HOST, port=_PG_PORT, dbname=_PG_DB,
            user=_PG_USER, password=_PG_PASSWORD,
            connect_timeout=3,
        )
        cur = conn.cursor()
        cur.execute(
            "SELECT id FROM orders WHERE user_id = %s AND created_at >= %s LIMIT 1",
            (user_id, after_epoch_s),
        )
        found = cur.fetchone() is not None
        cur.close()
        conn.close()
        return found
    except Exception:
        return None


def _parse_chain_c_sse(
    sse_events: list[dict[str, Any]],
    expects_delegation: bool,
    task_type: str = "",
) -> tuple[bool, str, dict[str, Any]]:
    """分析链路 C SSE 事件流，返回 (degraded, signals, metrics)。

    ADK 通过 transfer_to_agent 工具委托给子 Agent，不直接暴露内部工具名。
    检测策略：
      - remote_agent_delegated: TOOL_CALL_START.toolCallName == "transfer_to_agent"
      - order_draft_seen:       TOOL_CALL_ARGS 或文本响应含订单草稿相关词
      - order_submit_seen:      文本响应含提交/下单完成相关词
      - no_order_draft:         order_create_draft turn 结束但 order_draft_seen=False
      - no_order_submit:        order_submit turn 结束但 order_submit_seen=False
    """
    order_draft_seen = False
    order_submit_seen = False
    remote_agent_delegated = False
    run_error_seen = False
    text_chars = 0
    response_text = ""
    tool_args_text = ""

    for ev in sse_events:
        ev_type = ev.get("type", "")
        if ev_type in ("TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CHUNK"):
            delta = ev.get("delta", "")
            text_chars += len(delta)
            response_text += delta
        elif ev_type == "TOOL_CALL_START":
            tool_name: str = (
                ev.get("toolCallName") or ev.get("functionName") or ev.get("function_name") or ""
            ).lower()
            if tool_name == _ADK_TRANSFER_TOOL:
                remote_agent_delegated = True
        elif ev_type == "TOOL_CALL_ARGS":
            tool_args_text += ev.get("delta", "")
        elif ev_type == "RUN_ERROR":
            run_error_seen = True

    # 通过 TOOL_CALL_ARGS 或响应文本判断是否涉及订单草稿/提交
    combined = (tool_args_text + response_text).lower()
    if any(sig in combined for sig in _ORDER_DRAFT_SIGNALS):
        order_draft_seen = True
    if any(sig in combined for sig in _ORDER_SUBMIT_SIGNALS):
        order_submit_seen = True

    signals: list[str] = []
    if run_error_seen:
        signals.append("run_error")
    if expects_delegation and not remote_agent_delegated and not run_error_seen:
        signals.append("no_delegation")
    if text_chars == 0 and not run_error_seen:
        signals.append("no_response_text")
    if task_type == "order_create_draft" and not order_draft_seen and not run_error_seen:
        signals.append("no_order_draft")
    if task_type == "order_submit" and not order_submit_seen and not run_error_seen:
        signals.append("no_order_submit")

    return bool(signals), ",".join(signals), {
        "order_draft_seen": order_draft_seen,
        "order_submit_seen": order_submit_seen,
        "remote_agent_delegated": remote_agent_delegated,
    }


def _chain_c_task(self: "HttpUser") -> None:
    """链路 C 任务：完整订单流程（create_draft → submit，同一 ADK 会话）。

    两轮消息共享同一 thread_id，Agent 在 order_submit turn 可感知上轮创建的草单，
    保证 order_submit_seen 信号在 baseline 下不产生误报。
    """
    thread_id = str(uuid.uuid4())

    for task_def in _CHAIN_C_TASKS_DEF:
        task_type: str = task_def["task_type"]
        expects_delegation: bool = task_def["expects_delegation"]  # type: ignore[assignment]
        message: str = task_def["message"]

        run_id = str(uuid.uuid4())
        request_id = str(uuid.uuid4())
        payload = _make_chain_c_payload(thread_id, run_id, message)
        headers = _base_headers(request_id)
        headers["Accept"] = "text/event-stream"

        start_epoch_s = int(time.time())
        t_start = time.monotonic()
        with self.client.post(
            "/",
            json=payload,
            headers=headers,
            catch_response=True,
            name=f"/ (order-{task_type})",
        ) as resp:
            elapsed_ms = int((time.monotonic() - t_start) * 1000)
            if resp.status_code in (200, 201):
                resp.success()
                error_text = ""
            else:
                error_text = (resp.text or "")[:300]
                resp.failure(f"HTTP {resp.status_code}")

            sse_events_list = _parse_sse_events(resp.content)
            degraded, signals, metrics = _parse_chain_c_sse(sse_events_list, expects_delegation, task_type)

            row = _base_row(request_id, resp.status_code, elapsed_ms, error_text)
            row["turn_type"] = task_type
            row.update(metrics)
            row["degraded"] = degraded
            row["degradation_signals"] = signals
            _write_row(row)


# ── 链路路由：根据 CHAIN 暴露单一 HttpUser ────────────────────────────────────────

if CHAIN == "b":

    class LocustUser(HttpUser):
        """链路 B — AG-UI ReAct 对话（trip-itinerary-planner）"""
        host = TARGET_HOST
        wait_time = between(2, 5)
        tasks = [_chain_b_task]

        def on_start(self) -> None:
            self._b_thread_id = str(uuid.uuid4())
            self._b_turn_idx = 0
            self._b_itinerary_id = _CHAIN_B_ID_POOL[
                next(_chain_b_id_counter) % len(_CHAIN_B_ID_POOL)
            ]

elif CHAIN == "c":

    class LocustUser(HttpUser):  # type: ignore[no-redef]
        """链路 C — AG-UI ADK 跨服务（trip-chat-service）"""
        host = TARGET_HOST
        wait_time = between(3, 8)
        tasks = [_chain_c_task]

        def on_start(self) -> None:
            pass

else:  # "a"

    class LocustUser(HttpUser):  # type: ignore[no-redef]
        """链路 A — REST 规划接口（trip-itinerary-planner）"""
        host = TARGET_HOST
        wait_time = between(1, 3)
        tasks = [_chain_a_task]
