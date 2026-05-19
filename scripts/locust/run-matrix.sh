#!/usr/bin/env bash
# TripSphere Locust 四象限压测入口（三链路统一）
#
# 使用方法（单象限）：
#   bash scripts/locust/run-matrix.sh <CELL> [SCENARIO] [USERS] [SPAWN_RATE] [DURATION] [MODE]
#
# 使用方法（全矩阵顺序执行）：
#   bash scripts/locust/run-matrix.sh all [FAULT_SCENARIO] [LOW_USERS] [HIGH_USERS] [DURATION]
#
# 参数：
#   CELL          baseline-low | fault-low | baseline-high | fault-high | all
#   SCENARIO      fault_scenarios.yaml 中的场景名；baseline 象限留空或 ""
#   USERS         并发用户数（单象限）
#   SPAWN_RATE    每秒新增用户数，默认 2
#   DURATION      压测时长，如 15m / 30m，默认 15m
#   MODE          single | combo，默认 single
#
# 全矩阵（all）时：
#   LOW_USERS     低压并发用户数，默认 10
#   HIGH_USERS    高压并发用户数，默认 50
#   DURATION      每个象限的持续时间，默认 15m
#   FAULT_SCENARIO 故障场景名，同时用于 fault-low / fault-high 两个象限
#
# 环境变量（权重高于参数）：
#   CHAIN         a | b | c，默认 a（选择压测链路）
#   TARGET_HOST   目标服务地址（不填时按链路使用默认值）
#   USER_ID       x-user-id，默认 42
#   PAYLOAD_MODE  fixed | mixed，默认 fixed（仅链路 A）
#   ARTIFACT_DIR  输出根目录，默认 artifacts/locust
#   SCENARIO_MODE single | combo，默认 single
#   SEED          payload 随机种子，默认 42
#   CHAIN_B_ITINERARY_ID  预置行程 ID（链路 B 专用）
#   CHAIN_B_DESTINATION   行程目的地（链路 B 专用，默认 Shanghai）
#
# 先决条件（按链路）：
#   链路 A：trip-itinerary-planner 容器已启动，FAULT_INJECTION_ENABLED=true
#   链路 B：同上（同服务，入口为 / 而非 /api/v1/itineraries/plannings）
#   链路 C：trip-chat-service 容器已启动，FAULT_INJECTION_ENABLED=true
#           order_agent_drop 场景还需 FAULT_INJECTION_SCENARIO 全局配置
#   所有链路：uv 已安装（https://docs.astral.sh/uv/）
#
# 示例：
#   # 链路 A — 低压 baseline
#   bash scripts/locust/run-matrix.sh baseline-low "" 10 2 15m
#
#   # 链路 A — 低压故障（geocoding 延迟）
#   bash scripts/locust/run-matrix.sh fault-low geocode_latency 10 2 15m
#
#   # 链路 B — 低压故障（ReAct LLM 延迟）
#   CHAIN=b bash scripts/locust/run-matrix.sh fault-low chat_llm_latency 5 1 15m
#
#   # 链路 B — 高压故障（强制路由提前结束）
#   CHAIN=b bash scripts/locust/run-matrix.sh fault-high route_force_end 20 3 15m
#
#   # 链路 C — 低压故障（A2A Trace 断链）
#   CHAIN=c bash scripts/locust/run-matrix.sh fault-low a2a_trace_drop 3 1 15m
#
#   # 链路 B — 全矩阵
#   CHAIN=b bash scripts/locust/run-matrix.sh all chat_llm_latency 5 20 15m
#
#   # 链路 A — 全矩阵 + combo 场景
#   SCENARIO_MODE=combo bash scripts/locust/run-matrix.sh all cascade_degrade 10 50 15m

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCUSTFILE="${SCRIPT_DIR}/locustfile.py"

CHAIN="${CHAIN:-a}"

# 按链路设置默认 host
case "$CHAIN" in
  b) _DEFAULT_HOST="http://localhost:24215" ;;
  c) _DEFAULT_HOST="http://localhost:24210" ;;
  *) _DEFAULT_HOST="http://localhost:24215" ;;
esac

TARGET_HOST="${TARGET_HOST:-${_DEFAULT_HOST}}"
USER_ID_ENV="${USER_ID:-42}"
PAYLOAD_MODE="${PAYLOAD_MODE:-fixed}"
ARTIFACT_DIR="${ARTIFACT_DIR:-artifacts/locust}"
SEED="${SEED:-42}"
CHAIN_B_ITINERARY_ID="${CHAIN_B_ITINERARY_ID:-locust-placeholder-itin}"
CHAIN_B_DESTINATION="${CHAIN_B_DESTINATION:-Shanghai}"

# ── 帮助函数 ──────────────────────────────────────────────────────────────────

_sep() { printf '═%.0s' {1..64}; echo; }

_print_tempo_hints() {
    local exp_id="$1"
    printf '\nTempo TraceQL:\n'
    case "$CHAIN" in
      b)
        printf '  { resource.service.name = "trip-itinerary-planner" && .experiment.id = "%s" }\n' "$exp_id"
        printf '  { resource.service.name = "trip-itinerary-planner" && .experiment.id = "%s" && .fault.injected = true }\n' "$exp_id"
        ;;
      c)
        printf '  { resource.service.name = "trip-chat-service" && .experiment.id = "%s" }\n' "$exp_id"
        printf '  { resource.service.name = "trip-order-assistant" && .experiment.id = "%s" }\n' "$exp_id"
        printf '  { .experiment.id = "%s" && .fault.injected = true }\n' "$exp_id"
        ;;
      *)
        printf '  { resource.service.name = "trip-itinerary-planner" && .experiment.id = "%s" }\n' "$exp_id"
        printf '  { resource.service.name = "trip-itinerary-planner" && .experiment.id = "%s" && .fault.injected = true }\n' "$exp_id"
        ;;
    esac
    echo
}

_run_cell() {
    local cell="$1"
    local scenario="$2"
    local users="$3"
    local spawn_rate="$4"
    local duration="$5"
    local mode="${6:-${SCENARIO_MODE:-single}}"

    local ts
    ts="$(date +%Y%m%d%H%M)"
    local fault_label="${scenario:-none}"
    local experiment_id="locust-chain${CHAIN}-${cell}-${fault_label}-${ts}"
    local out_dir="${ARTIFACT_DIR}/${experiment_id}"

    mkdir -p "${out_dir}"

    _sep
    printf 'TripSphere Locust Matrix Run\n'
    printf '  chain         : %s\n' "$CHAIN"
    printf '  cell          : %s\n' "$cell"
    printf '  scenario      : %s (%s mode)\n' "${scenario:-<baseline>}" "$mode"
    printf '  users         : %s  spawn_rate: %s  duration: %s\n' "$users" "$spawn_rate" "$duration"
    printf '  target        : %s\n' "$TARGET_HOST"
    printf '  experiment_id : %s\n' "$experiment_id"
    printf '  output        : %s\n' "$out_dir"
    _sep

    CHAIN="$CHAIN" \
    TARGET_HOST="$TARGET_HOST" \
    EXPERIMENT_ID="$experiment_id" \
    FAULT_SCENARIO="$scenario" \
    SCENARIO_MODE="$mode" \
    USER_ID="$USER_ID_ENV" \
    PAYLOAD_MODE="$PAYLOAD_MODE" \
    MATRIX_CELL="$cell" \
    ARTIFACT_DIR="$ARTIFACT_DIR" \
    SEED="$SEED" \
    CHAIN_B_ITINERARY_ID="$CHAIN_B_ITINERARY_ID" \
    CHAIN_B_DESTINATION="$CHAIN_B_DESTINATION" \
    uv run --project "${SCRIPT_DIR}" locust \
        -f "${LOCUSTFILE}" \
        --headless \
        --users "$users" \
        --spawn-rate "$spawn_rate" \
        --run-time "$duration" \
        --host "$TARGET_HOST" \
        --csv "${out_dir}/stats" \
        --html "${out_dir}/report.html" \
        --logfile "${out_dir}/run.log" \
        2>&1 | tee "${out_dir}/console.log"

    printf '\nDone: %s\n' "$out_dir"
    _print_tempo_hints "$experiment_id"

    if [[ -f "${out_dir}/quality.csv" ]]; then
        printf 'Generating degradation report...\n'
        uv run --project "${SCRIPT_DIR}" python3 "${SCRIPT_DIR}/generate_report.py" \
            "${out_dir}/quality.csv" 2>&1 | tail -3
        printf 'Degradation report: %s/degradation_report.html\n\n' "$out_dir"
    else
        printf 'No quality.csv found, skipping degradation report.\n\n'
    fi
}

# ── 入口 ─────────────────────────────────────────────────────────────────────────

CELL="${1:-baseline-low}"

if [[ "$CELL" == "all" ]]; then
    FAULT_SCENARIO="${2:-}"
    LOW_USERS="${3:-10}"
    HIGH_USERS="${4:-50}"
    DURATION="${5:-15m}"
    MODE="${SCENARIO_MODE:-single}"
    SPAWN_LOW=$(( LOW_USERS / 5 < 1 ? 1 : LOW_USERS / 5 ))
    SPAWN_HIGH=$(( HIGH_USERS / 10 < 1 ? 1 : HIGH_USERS / 10 ))

    printf 'Running full 2×2 matrix — chain=%s scenario=%s\n' "$CHAIN" "${FAULT_SCENARIO:-<baseline only>}"

    _run_cell "baseline-low"  ""               "$LOW_USERS"  "$SPAWN_LOW"  "$DURATION" "$MODE"
    _run_cell "fault-low"     "$FAULT_SCENARIO" "$LOW_USERS"  "$SPAWN_LOW"  "$DURATION" "$MODE"
    _run_cell "baseline-high" ""               "$HIGH_USERS" "$SPAWN_HIGH" "$DURATION" "$MODE"
    _run_cell "fault-high"    "$FAULT_SCENARIO" "$HIGH_USERS" "$SPAWN_HIGH" "$DURATION" "$MODE"

    printf 'All 4 cells complete. Artifacts: %s/\n' "$ARTIFACT_DIR"
else
    SCENARIO="${2:-}"
    USERS="${3:-10}"
    SPAWN_RATE="${4:-2}"
    DURATION="${5:-15m}"
    MODE="${6:-${SCENARIO_MODE:-single}}"

    _run_cell "$CELL" "$SCENARIO" "$USERS" "$SPAWN_RATE" "$DURATION" "$MODE"
fi
