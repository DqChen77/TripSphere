"""TripSphere Locust 压测脚本 — 链路 A：POST /api/v1/itineraries/plannings

每个 Locust 用户循环发送规划请求，头部注入 experiment_id 和 fault_scenario DSL，
解析响应的业务质量指标，并追加写入 quality.csv（与 Locust 自带 stats.csv 并列）。

环境变量（也可通过 run-matrix.sh 传入）：
  TARGET_HOST     HTTP 地址，默认 http://localhost:24215
  EXPERIMENT_ID   实验主键，写入 x-experiment-id 头
  FAULT_SCENARIO  fault_scenarios.yaml 中的场景名，空字符串 = baseline
  SCENARIO_MODE   single | combo，默认 single
  USER_ID         x-user-id，默认 42
  PAYLOAD_MODE    fixed | mixed，默认 fixed（目的地固定为上海）
  MATRIX_CELL     四象限标签（baseline-low/fault-low/baseline-high/fault-high）
  ARTIFACT_DIR    输出目录，默认 artifacts/locust
  SEED            payload 目的地采样随机种子，默认 42；不影响服务端概率门

故障命中说明：
  每个故障的 probability 由服务端 FaultRegistry 独立抽样决定，Locust 只透传 DSL。
  实际命中次数请通过 Tempo TraceQL `fault.injected=true` 查询，
  本脚本侧的 quality.csv 只记录"声明的 DSL 和请求结果"，两者严格区分。
"""

import csv
import json
import os
import random
import time
import uuid
from pathlib import Path
from typing import Any

import yaml
from locust import HttpUser, between, events, task

# ── 配置 ────────────────────────────────────────────────────────────────────────

_SCRIPT_DIR = Path(__file__).parent
_SCENARIOS_FILE = _SCRIPT_DIR / "fault_scenarios.yaml"

TARGET_HOST = os.environ.get("TARGET_HOST", "http://localhost:24215")
EXPERIMENT_ID = os.environ.get("EXPERIMENT_ID", f"locust-{int(time.time())}")
FAULT_SCENARIO = os.environ.get("FAULT_SCENARIO", "")
SCENARIO_MODE = os.environ.get("SCENARIO_MODE", "single")
USER_ID = os.environ.get("USER_ID", "42")
PAYLOAD_MODE = os.environ.get("PAYLOAD_MODE", "fixed")
MATRIX_CELL = os.environ.get("MATRIX_CELL", "baseline-low")
ARTIFACT_DIR = Path(os.environ.get("ARTIFACT_DIR", "artifacts/locust")) / EXPERIMENT_ID
SEED = int(os.environ.get("SEED", "42"))

# 降级检测阈值（可通过环境变量调整）
DEGRADE_MIN_DAY_PLANS = int(os.environ.get("DEGRADE_MIN_DAY_PLANS", "1"))
DEGRADE_MIN_ACTIVITIES = int(os.environ.get("DEGRADE_MIN_ACTIVITIES", "1"))
# 每天平均活动数低于此值视为活动内容稀疏降级（activity_count / day_plan_count）
# 默认 2.0：3 天行程总活动数 ≤ 6 时触发；正常情况约 4 活动/天
DEGRADE_MIN_ACTIVITIES_PER_DAY = float(os.environ.get("DEGRADE_MIN_ACTIVITIES_PER_DAY", "2.0"))
DEGRADE_MIN_MARKDOWN_LEN = int(os.environ.get("DEGRADE_MIN_MARKDOWN_LEN", "200"))

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
    """从 fault_scenarios.yaml 中读取场景，拼接 x-fault-scenario DSL 字符串。

    空场景名返回空字符串（baseline 不注入故障）。
    多个 fault 条目用 ";" 连接，每个条目格式：
      <target>.<primitive>=<value>[,message=<msg>][,probability=<p>]
    """
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
            raw_msg = str(fault["message"])
            # message 里若包含 = 号（如 "field=attractions,n=1"），整段作为 message 值透传
            segment += f",message={raw_msg}"
        prob = fault.get("probability")
        if prob is not None and prob != 1.0:
            segment += f",probability={prob}"
        parts.append(segment)
    return ";".join(parts)


_FAULT_DSL: str = _build_fault_dsl(_SCENARIOS_FILE, FAULT_SCENARIO, SCENARIO_MODE)


# ── Payload 工厂 ─────────────────────────────────────────────────────────────────

def _make_payload() -> dict[str, Any]:
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


# ── 响应质量解析 ──────────────────────────────────────────────────────────────────

def _parse_quality(body: bytes, status_code: int) -> dict[str, Any]:
    """从规划接口响应体中提取业务质量指标。

    成功响应的结构：
      { itinerary: { id, day_plans: [{ activities: [...] }] }, markdown_content, messages }
    """
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
        activity_count = sum(
            len(day.get("activities") or []) for day in day_plans
        )
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


_DEFAULT_COORDS = (121.4737, 31.2304)  # 高德降级时的默认上海中心坐标
_COORD_CLUSTER_THRESHOLD = 0.01        # 度；活动坐标散布小于此值视为"聚集于同一点"
_GEOCODING_FALLBACK_THRESHOLD = 0.5   # 度；距上海默认点的最大允许距离


def _extract_activity_coords(data: dict[str, Any]) -> list[tuple[float, float]]:
    """从响应体中提取所有活动的 (longitude, latitude)，排除全零坐标。"""
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
    """判断活动坐标是否显示高德地理编码降级。

    两种判定逻辑（任一成立即认为降级）：
    1. 目的地不是上海，但大多数活动坐标距上海默认中心点 < 0.5 度
       （说明用了默认上海坐标而非真实地址）
    2. 所有活动坐标互相之间的散布 < 0.01 度
       （说明所有活动坐标完全相同，是单一 fallback 中心点的典型特征）
    """
    if not coords or len(coords) < 2:
        return False

    lon_vals = [c[0] for c in coords]
    lat_vals = [c[1] for c in coords]

    # 规则 2：坐标聚集于同一点（适用于所有目的地包括上海）
    lon_spread = max(lon_vals) - min(lon_vals)
    lat_spread = max(lat_vals) - min(lat_vals)
    if lon_spread < _COORD_CLUSTER_THRESHOLD and lat_spread < _COORD_CLUSTER_THRESHOLD:
        return True

    # 规则 1：非上海目的地但坐标落在上海默认中心附近
    if "shanghai" not in destination.lower():
        near_default = sum(
            1 for lon, lat in coords
            if abs(lon - _DEFAULT_COORDS[0]) < _GEOCODING_FALLBACK_THRESHOLD
            and abs(lat - _DEFAULT_COORDS[1]) < _GEOCODING_FALLBACK_THRESHOLD
        )
        if near_default / len(coords) >= 0.8:
            return True

    return False


def _detect_degradation(
    quality: dict[str, Any],
    status_code: int,
    raw_body: bytes,
    destination: str,
) -> tuple[bool, str]:
    """在 HTTP 成功响应中检测业务质量退化信号。

    返回 (degraded: bool, signals: 逗号分隔字符串)。
    降级 ≠ 错误：HTTP 仍然 2xx，但系统走了 fallback 路径导致产物质量下降。

    信号说明：
      geocoding_fallback  活动坐标聚集于同一点或落在上海默认坐标附近，说明高德降级
      no_day_plans        day_plan_count 低于阈值 → 结构化 LLM 或景点双失败，骨架为空
      no_activities       有 day_plans 但活动总数低于阈值 → 景点/酒店服务降级
      short_markdown      markdown 长度低于阈值 → markdown LLM 失败，走本地兜底
      no_id               has_id=False 但状态码成功 → 持久化异常边缘情况

    注意：geocoding_fallback 在固定目的地为上海时规则 1 不适用，
    规则 2（坐标完全聚集）仍然有效。Tempo trace 的 fault.fallback_path 是最终判据。
    """
    if status_code not in (200, 201):
        return False, ""

    signals: list[str] = []

    # geocoding 降级检测（需要解析活动坐标）
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
                signals.append("sparse_activities")   # 活动数量稀疏，attraction 服务可能降级
    if quality["markdown_length"] < DEGRADE_MIN_MARKDOWN_LEN:
        signals.append("short_markdown")
    if not quality["has_id"]:
        signals.append("no_id")

    return bool(signals), ",".join(signals)


# ── CSV 输出 ─────────────────────────────────────────────────────────────────────

_CSV_FIELDS = [
    "ts",
    "request_id",
    "experiment_id",
    "matrix_cell",
    "fault_scenario_name",
    "scenario_mode",
    "fault_dsl",
    "status_code",
    "elapsed_ms",
    "itinerary_id",
    "day_plan_count",
    "activity_count",
    "markdown_length",
    "has_id",
    # 降级检测：HTTP 成功但业务质量退化（fallback 路径被激活）
    # 与 error 字段互斥：error 非空时 degraded 无意义，不填
    # 具体 fallback 原因需通过 Tempo trace fault.fallback_path 属性确认
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
    _quality_writer = csv.DictWriter(_quality_file, fieldnames=_CSV_FIELDS)
    _quality_writer.writeheader()
    _quality_file.flush()

    print(f"\n[TripSphere Locust]")
    print(f"  experiment_id : {EXPERIMENT_ID}")
    print(f"  matrix_cell   : {MATRIX_CELL}")
    print(f"  fault_scenario: {FAULT_SCENARIO or '<baseline>'}")
    print(f"  scenario_mode : {SCENARIO_MODE}")
    print(f"  fault_dsl     : {_FAULT_DSL or '<none>'}")
    print(f"  artifact_dir  : {ARTIFACT_DIR}")
    print(f"  degrade thresholds: day_plans>={DEGRADE_MIN_DAY_PLANS}, "
          f"activities>={DEGRADE_MIN_ACTIVITIES}, markdown>={DEGRADE_MIN_MARKDOWN_LEN}")


@events.quitting.add_listener
def _on_quit(environment: Any, **_: Any) -> None:
    global _quality_file
    if _quality_file is not None:
        _quality_file.flush()
        _quality_file.close()
        _quality_file = None

    # 打印降级摘要
    total = _total_success + _total_degraded
    rate = (_total_degraded / total * 100) if total > 0 else 0.0
    print(f"\n{'='*60}")
    print(f"[TripSphere Locust] Degradation Summary")
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


# ── Locust 用户 ───────────────────────────────────────────────────────────────────

class PlannerUser(HttpUser):
    """模拟 TripSphere 链路 A：POST /api/v1/itineraries/plannings"""

    host = TARGET_HOST
    wait_time = between(1, 3)

    @task
    def plan_itinerary(self) -> None:
        global _total_success, _total_degraded

        payload = _make_payload()
        destination: str = payload["destination"]
        request_id = str(uuid.uuid4())
        headers: dict[str, str] = {
            "Content-Type": "application/json",
            "x-user-id": USER_ID,
            "x-experiment-id": EXPERIMENT_ID,
            "x-request-id": request_id,
        }
        if _FAULT_DSL:
            headers["x-fault-scenario"] = _FAULT_DSL

        t_start = time.monotonic()

        with self.client.post(
            "/api/v1/itineraries/plannings",
            json=payload,
            headers=headers,
            catch_response=True,
            name="/api/v1/itineraries/plannings",
        ) as resp:
            elapsed_ms = int((time.monotonic() - t_start) * 1000)
            quality = _parse_quality(resp.content, resp.status_code)

            if resp.status_code in (200, 201):
                resp.success()
                error_text = ""
            else:
                error_text = (resp.text or "")[:300]
                resp.failure(f"HTTP {resp.status_code}")

            degraded, degradation_signals = _detect_degradation(
                quality, resp.status_code, resp.content, destination
            )

            # 累计降级统计
            if resp.status_code in (200, 201):
                if degraded:
                    _total_degraded += 1
                    for sig in degradation_signals.split(","):
                        if sig:
                            _degradation_counts[sig] = _degradation_counts.get(sig, 0) + 1
                else:
                    _total_success += 1

            if _quality_writer is not None:
                _quality_writer.writerow(
                    {
                        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
                        "request_id": request_id,
                        "experiment_id": EXPERIMENT_ID,
                        "matrix_cell": MATRIX_CELL,
                        "fault_scenario_name": FAULT_SCENARIO,
                        "scenario_mode": SCENARIO_MODE,
                        "fault_dsl": _FAULT_DSL,
                        "status_code": resp.status_code,
                        "elapsed_ms": elapsed_ms,
                        "degraded": degraded,
                        "degradation_signals": degradation_signals,
                        "error": error_text,
                        **quality,
                    }
                )
                _quality_file.flush()
