#!/usr/bin/env bash
# TripSphere Locust 四象限压测入口
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
# 环境变量覆盖（权重高于参数）：
#   TARGET_HOST   目标服务地址，默认 http://localhost:24215
#   USER_ID       x-user-id，默认 42
#   PAYLOAD_MODE  fixed | mixed，默认 fixed
#   ARTIFACT_DIR  输出根目录，默认 artifacts/locust
#   SCENARIO_MODE single | combo，默认 single
#   SEED          payload 随机种子，默认 42
#
# 先决条件：
#   1. trip-itinerary-planner 容器已启动，且 FAULT_INJECTION_ENABLED=true
#   2. uv 已安装（https://docs.astral.sh/uv/）
#   3. 首次运行时 uv 会自动下载 locust / pyyaml
#
# 示例：
#   # 低压 baseline（不注入故障）
#   bash scripts/locust/run-matrix.sh baseline-low "" 10 2 15m
#
#   # 低压故障（geocoding 4s 延迟，100% 概率）
#   bash scripts/locust/run-matrix.sh fault-low geocode_latency 10 2 15m
#
#   # 高压故障（组合延迟+景点异常，combo 模式）
#   SCENARIO_MODE=combo bash scripts/locust/run-matrix.sh fault-high cascade_degrade 50 5 15m combo
#
#   # 全矩阵顺序执行（自动跑 4 个象限）
#   bash scripts/locust/run-matrix.sh all geocode_latency 10 50 15m

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCUSTFILE="${SCRIPT_DIR}/locustfile.py"

TARGET_HOST="${TARGET_HOST:-http://localhost:24215}"
USER_ID_ENV="${USER_ID:-42}"
PAYLOAD_MODE="${PAYLOAD_MODE:-fixed}"
ARTIFACT_DIR="${ARTIFACT_DIR:-artifacts/locust}"
SEED="${SEED:-42}"

# ── 帮助函数 ──────────────────────────────────────────────────────────────────

_sep() { printf '═%.0s' {1..64}; echo; }

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
    local experiment_id="locust-${cell}-${fault_label}-${ts}"
    local out_dir="${ARTIFACT_DIR}/${experiment_id}"

    mkdir -p "${out_dir}"

    _sep
    printf 'TripSphere Locust Matrix Run\n'
    printf '  cell          : %s\n' "$cell"
    printf '  scenario      : %s (%s mode)\n' "${scenario:-<baseline>}" "$mode"
    printf '  users         : %s  spawn_rate: %s  duration: %s\n' "$users" "$spawn_rate" "$duration"
    printf '  target        : %s\n' "$TARGET_HOST"
    printf '  experiment_id : %s\n' "$experiment_id"
    printf '  output        : %s\n' "$out_dir"
    _sep

    TARGET_HOST="$TARGET_HOST" \
    EXPERIMENT_ID="$experiment_id" \
    FAULT_SCENARIO="$scenario" \
    SCENARIO_MODE="$mode" \
    USER_ID="$USER_ID_ENV" \
    PAYLOAD_MODE="$PAYLOAD_MODE" \
    MATRIX_CELL="$cell" \
    ARTIFACT_DIR="$ARTIFACT_DIR" \
    SEED="$SEED" \
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
    printf 'Tempo TraceQL:\n'
    printf '  { resource.service.name = "trip-itinerary-planner" && .experiment.id = "%s" }\n' \
        "$experiment_id"
    printf '  { resource.service.name = "trip-itinerary-planner" && .experiment.id = "%s" && .fault.injected = true }\n' \
        "$experiment_id"
    echo
}

# ── 入口 ─────────────────────────────────────────────────────────────────────────

CELL="${1:-baseline-low}"

if [[ "$CELL" == "all" ]]; then
    # 全矩阵模式：顺序跑 baseline-low → fault-low → baseline-high → fault-high
    FAULT_SCENARIO="${2:-}"
    LOW_USERS="${3:-10}"
    HIGH_USERS="${4:-50}"
    DURATION="${5:-15m}"
    MODE="${SCENARIO_MODE:-single}"
    SPAWN_LOW=$(( LOW_USERS / 5 < 1 ? 1 : LOW_USERS / 5 ))
    SPAWN_HIGH=$(( HIGH_USERS / 10 < 1 ? 1 : HIGH_USERS / 10 ))

    printf 'Running full 2x2 matrix for scenario: %s\n' "${FAULT_SCENARIO:-<baseline only>}"

    _run_cell "baseline-low"  ""               "$LOW_USERS"  "$SPAWN_LOW"  "$DURATION" "$MODE"
    _run_cell "fault-low"     "$FAULT_SCENARIO" "$LOW_USERS"  "$SPAWN_LOW"  "$DURATION" "$MODE"
    _run_cell "baseline-high" ""               "$HIGH_USERS" "$SPAWN_HIGH" "$DURATION" "$MODE"
    _run_cell "fault-high"    "$FAULT_SCENARIO" "$HIGH_USERS" "$SPAWN_HIGH" "$DURATION" "$MODE"

    printf 'All 4 cells complete. Artifacts: %s/\n' "$ARTIFACT_DIR"
else
    # 单象限模式
    SCENARIO="${2:-}"
    USERS="${3:-10}"
    SPAWN_RATE="${4:-2}"
    DURATION="${5:-15m}"
    MODE="${6:-${SCENARIO_MODE:-single}}"

    _run_cell "$CELL" "$SCENARIO" "$USERS" "$SPAWN_RATE" "$DURATION" "$MODE"
fi
