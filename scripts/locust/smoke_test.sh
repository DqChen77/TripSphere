#!/usr/bin/env bash
# 三链路 2 分钟冒烟测试 + degradation report 生成
# 用法：
#   bash scripts/locust/smoke_test.sh
#   CHAIN_B_ITINERARY_ID=xxx bash scripts/locust/smoke_test.sh
#
# 如果未设置 CHAIN_B_ITINERARY_ID，脚本会尝试从最近的链路 A artifacts 自动提取

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DURATION="3m"
ARTIFACT_DIR="${ARTIFACT_DIR:-${REPO_ROOT}/artifacts/locust}"
HIGH_USERS="${HIGH_USERS:-10}"
FAULT_SCENARIO_A="${FAULT_SCENARIO_A:-chaina_final}"
FAULT_SCENARIO_B="${FAULT_SCENARIO_B:-chainb_final}"
FAULT_SCENARIO_C="${FAULT_SCENARIO_C:-chainc_final}"
SKIP_BASELINE="${SKIP_BASELINE:-0}"

# ── 颜色输出 ─────────────────────────────────────────────────────────────────
_info()  { printf '\033[1;34m[INFO]\033[0m  %s\n' "$*"; }
_ok()    { printf '\033[1;32m[OK]\033[0m    %s\n' "$*"; }
_warn()  { printf '\033[1;33m[WARN]\033[0m  %s\n' "$*"; }
_sep()   { printf '─%.0s' {1..64}; echo; }

# ── 自动获取 Chain B itinerary ID 池 ─────────────────────────────────────────
_auto_itinerary_id() {
    local latest_csv
    latest_csv="$(ls -td "${ARTIFACT_DIR}"/locust-chaina-*/quality.csv 2>/dev/null | head -1)"
    if [[ -z "$latest_csv" ]]; then
        echo ""
        return
    fi
    # 取所有 itinerary_id，去重后逗号拼接（最多 10 个），分发给各用户避免写冲突
    awk -F',' '
        NR==1 { for(i=1;i<=NF;i++) if($i=="itinerary_id") col=i }
        NR>1 && col && $col!="" { print $col }
    ' "$latest_csv" | sort -u | head -10 | paste -sd ','
}

# ── 运行单个象限并生成 report ─────────────────────────────────────────────────
_run_and_report() {
    local chain="$1"
    local extra_env="${2:-}"

    _sep
    _info "Chain ${chain^^} — baseline-low ${DURATION}"

    local env_prefix="CHAIN=${chain} ARTIFACT_DIR=${ARTIFACT_DIR}"
    [[ -n "$extra_env" ]] && env_prefix="${extra_env} ${env_prefix}"

    eval "${env_prefix} bash '${SCRIPT_DIR}/run-matrix.sh' baseline-low \"\" 3 1 ${DURATION}" || true

    # 找到刚生成的实验目录
    local exp_dir
    exp_dir="$(ls -td "${ARTIFACT_DIR}"/locust-chain${chain}-baseline-low-none-* 2>/dev/null | head -1)"

    if [[ -z "$exp_dir" || ! -f "${exp_dir}/quality.csv" ]]; then
        _warn "未找到 quality.csv，跳过 degradation report"
        return
    fi

    _info "生成 degradation report: ${exp_dir}/degradation_report.html"
    uv run --project "${SCRIPT_DIR}" python3 "${SCRIPT_DIR}/generate_report.py" \
        "${exp_dir}/quality.csv" 2>&1 | tail -3

    _ok "Chain ${chain^^} 完成"
    printf '  报告：%s/degradation_report.html\n' "$exp_dir"
    printf '  CSV：%s/quality.csv\n' "$exp_dir"
}

# ── 运行 fault-high 象限并生成 report ────────────────────────────────────────
_run_fault_high() {
    local chain="$1"
    local scenario="$2"
    local extra_env="${3:-}"

    _sep
    _info "Chain ${chain^^} — fault-high ${DURATION} (scenario: ${scenario})"

    local env_prefix="CHAIN=${chain} ARTIFACT_DIR=${ARTIFACT_DIR}"
    [[ -n "$extra_env" ]] && env_prefix="${extra_env} ${env_prefix}"

    eval "${env_prefix} SCENARIO_MODE=combo bash '${SCRIPT_DIR}/run-matrix.sh' fault-high '${scenario}' ${HIGH_USERS} 2 ${DURATION}" || true

    local exp_dir
    exp_dir="$(ls -td "${ARTIFACT_DIR}"/locust-chain${chain}-fault-high-${scenario}-* 2>/dev/null | head -1)"

    if [[ -z "$exp_dir" || ! -f "${exp_dir}/quality.csv" ]]; then
        _warn "未找到 quality.csv，跳过 degradation report"
        return
    fi

    _info "生成 degradation report: ${exp_dir}/degradation_report.html"
    uv run --project "${SCRIPT_DIR}" python3 "${SCRIPT_DIR}/generate_report.py" \
        "${exp_dir}/quality.csv" 2>&1 | tail -3

    _ok "Chain ${chain^^} fault-high 完成"
    printf '  报告：%s/degradation_report.html\n' "$exp_dir"
    printf '  CSV：%s/quality.csv\n' "$exp_dir"
}

# ── 主流程 ────────────────────────────────────────────────────────────────────

_sep
_info "TripSphere 三链路冒烟测试（每链路 ${DURATION}）"
_sep

# ── Chain A ──────────────────────────────────────────────────────────────────
if [[ "$SKIP_BASELINE" == "1" ]]; then
    _warn "SKIP_BASELINE=1，跳过所有 baseline-low 象限"
else
    _run_and_report "a"
fi

# ── 解析 Chain B itinerary ID（Chain A 跑完后再取，确保能拿到最新 ID）─────────
CHAIN_B_ITINERARY_ID="${CHAIN_B_ITINERARY_ID:-}"
if [[ -z "$CHAIN_B_ITINERARY_ID" ]]; then
    CHAIN_B_ITINERARY_ID="$(_auto_itinerary_id)"
fi
[[ -n "$CHAIN_B_ITINERARY_ID" ]] && _info "Chain B itinerary ID: ${CHAIN_B_ITINERARY_ID}"

# ── Chain B（需要 itinerary ID）──────────────────────────────────────────────
if [[ "$SKIP_BASELINE" != "1" ]]; then
    if [[ -z "$CHAIN_B_ITINERARY_ID" ]]; then
        _warn "Chain B 跳过：未设置 CHAIN_B_ITINERARY_ID，且 artifacts 中无可用 ID"
        _warn "  手动运行：CHAIN_B_ITINERARY_ID=xxx bash scripts/locust/smoke_test.sh"
    else
        _run_and_report "b" "CHAIN_B_ITINERARY_ID=${CHAIN_B_ITINERARY_ID} TARGET_HOST=http://localhost:24215"
    fi
fi

# ── Chain C ──────────────────────────────────────────────────────────────────
if [[ "$SKIP_BASELINE" != "1" ]]; then
    _run_and_report "c"
fi

# ── fault-high 象限 ───────────────────────────────────────────────────────────
_sep
_info "fault-high 象限（${HIGH_USERS} 用户，每链路 ${DURATION}）"
_info "  Chain A scenario: ${FAULT_SCENARIO_A}"
_info "  Chain B scenario: ${FAULT_SCENARIO_B}"
_info "  Chain C scenario: ${FAULT_SCENARIO_C}"
_sep

_run_fault_high "a" "${FAULT_SCENARIO_A}"

if [[ -z "$CHAIN_B_ITINERARY_ID" ]]; then
    _warn "Chain B fault-high 跳过：未设置 CHAIN_B_ITINERARY_ID"
else
    _run_fault_high "b" "${FAULT_SCENARIO_B}" \
        "CHAIN_B_ITINERARY_ID=${CHAIN_B_ITINERARY_ID} TARGET_HOST=http://localhost:24215"
fi

_run_fault_high "c" "${FAULT_SCENARIO_C}"

# ── 汇总 ─────────────────────────────────────────────────────────────────────
_sep
_ok "全部完成，reports："
ls -dt "${ARTIFACT_DIR}"/locust-chain*-baseline-low-none-*/degradation_report.html \
       "${ARTIFACT_DIR}"/locust-chain*-fault-high-*/degradation_report.html 2>/dev/null \
    | head -9 \
    | while read -r f; do printf '  %s\n' "$f"; done
_sep
