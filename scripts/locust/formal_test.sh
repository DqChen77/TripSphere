#!/usr/bin/env bash
# TripSphere 正式实验脚本 — 三链路 × 四象限
#
# 顺序执行：
#   Chain A: baseline-low → baseline-high → fault-low → fault-high
#   Chain B: baseline-low → baseline-high → fault-low → fault-high
#   Chain C: baseline-low → baseline-high → fault-low → fault-high
#
# 总耗时约 12 × 10min = 120min
#
# 用法：bash scripts/locust/formal_test.sh
#   可覆盖时长：DURATION=5m bash scripts/locust/formal_test.sh

# 注意：不使用 set -e，因为 locust 在有降级请求时以 exit code 1 退出，
# 这是正常行为，不应中断后续实验。
set -uo pipefail

# ── 全局参数 ──────────────────────────────────────────────────
DURATION="${DURATION:-10m}"
ARTIFACT_DIR="${ARTIFACT_DIR:-artifacts/locust}"

# Chain A
A_LOW_USERS=5;   A_LOW_SPAWN=1
A_HIGH_USERS=20; A_HIGH_SPAWN=3
A_SCENARIO=chaina_final

# Chain B
B_LOW_USERS=3;   B_LOW_SPAWN=1
B_HIGH_USERS=10; B_HIGH_SPAWN=2
B_SCENARIO=chainb_final

# Chain C
C_LOW_USERS=3;   C_LOW_SPAWN=1
C_HIGH_USERS=8;  C_HIGH_SPAWN=2
C_SCENARIO=chainc_final

# ── 辅助函数 ─────────────────────────────────────────────────
_run() {
    # 包装 run-matrix.sh，忽略 locust 以 exit code 1 退出的情况
    # （locust 在有降级请求时返回 1，这是正常行为，不应中断实验序列）
    bash scripts/locust/run-matrix.sh "$@" || {
        local rc=$?
        echo "[WARN] run-matrix.sh exited with code $rc (continuing...)"
    }
}

_sep() {
    echo "========================================================"
}

# ── Chain A ───────────────────────────────────────────────────
_sep; echo " Chain A — baseline-low    $(date '+%H:%M:%S')"; _sep
CHAIN=a _run baseline-low "" $A_LOW_USERS $A_LOW_SPAWN $DURATION

_sep; echo " Chain A — baseline-high   $(date '+%H:%M:%S')"; _sep
CHAIN=a _run baseline-high "" $A_HIGH_USERS $A_HIGH_SPAWN $DURATION

_sep; echo " Chain A — fault-low       $(date '+%H:%M:%S')"; _sep
CHAIN=a SCENARIO_MODE=combo _run fault-low $A_SCENARIO $A_LOW_USERS $A_LOW_SPAWN $DURATION combo

_sep; echo " Chain A — fault-high      $(date '+%H:%M:%S')"; _sep
CHAIN=a SCENARIO_MODE=combo _run fault-high $A_SCENARIO $A_HIGH_USERS $A_HIGH_SPAWN $DURATION combo

# ── Chain B（从最新 Chain A baseline-low 提取行程 ID）──────────
_sep; echo " Chain B — 提取行程 ID     $(date '+%H:%M:%S')"; _sep

B_IDS=$(
  ls -td "${ARTIFACT_DIR}"/locust-chaina-baseline-low-none-*/quality.csv 2>/dev/null \
  | head -1 \
  | xargs awk -F',' '
      NR==1{for(i=1;i<=NF;i++) if($i=="itinerary_id") col=i}
      NR>1 && col && $col!=""{print $col}
    ' \
  | sort -u | head -10 | paste -sd ',' \
  || true
)
B_IDS="${B_IDS:-locust-placeholder-itin}"
echo "Chain B itinerary IDs: $B_IDS"

_sep; echo " Chain B — baseline-low    $(date '+%H:%M:%S')"; _sep
CHAIN=b CHAIN_B_ITINERARY_ID="$B_IDS" TARGET_HOST=http://localhost:24215 \
  _run baseline-low "" $B_LOW_USERS $B_LOW_SPAWN $DURATION

_sep; echo " Chain B — baseline-high   $(date '+%H:%M:%S')"; _sep
CHAIN=b CHAIN_B_ITINERARY_ID="$B_IDS" TARGET_HOST=http://localhost:24215 \
  _run baseline-high "" $B_HIGH_USERS $B_HIGH_SPAWN $DURATION

_sep; echo " Chain B — fault-low       $(date '+%H:%M:%S')"; _sep
CHAIN=b CHAIN_B_ITINERARY_ID="$B_IDS" TARGET_HOST=http://localhost:24215 \
  SCENARIO_MODE=combo _run fault-low $B_SCENARIO $B_LOW_USERS $B_LOW_SPAWN $DURATION combo

_sep; echo " Chain B — fault-high      $(date '+%H:%M:%S')"; _sep
CHAIN=b CHAIN_B_ITINERARY_ID="$B_IDS" TARGET_HOST=http://localhost:24215 \
  SCENARIO_MODE=combo _run fault-high $B_SCENARIO $B_HIGH_USERS $B_HIGH_SPAWN $DURATION combo

# ── Chain C ───────────────────────────────────────────────────
_sep; echo " Chain C — baseline-low    $(date '+%H:%M:%S')"; _sep
CHAIN=c _run baseline-low "" $C_LOW_USERS $C_LOW_SPAWN $DURATION

_sep; echo " Chain C — baseline-high   $(date '+%H:%M:%S')"; _sep
CHAIN=c _run baseline-high "" $C_HIGH_USERS $C_HIGH_SPAWN $DURATION

_sep; echo " Chain C — fault-low       $(date '+%H:%M:%S')"; _sep
CHAIN=c SCENARIO_MODE=combo _run fault-low $C_SCENARIO $C_LOW_USERS $C_LOW_SPAWN $DURATION combo

_sep; echo " Chain C — fault-high      $(date '+%H:%M:%S')"; _sep
CHAIN=c SCENARIO_MODE=combo _run fault-high $C_SCENARIO $C_HIGH_USERS $C_HIGH_SPAWN $DURATION combo

# ── 收尾：批量生成所有缺失报告 ────────────────────────────────
_sep
echo " 批量生成降级报告  $(date '+%H:%M:%S')"
_sep

shopt -s nullglob
for csv in "${ARTIFACT_DIR}"/locust-chain*/quality.csv; do
    dir="$(dirname "$csv")"
    report="$dir/degradation_report.html"
    if [[ ! -f "$report" ]]; then
        echo "  生成: $report"
        uv run --project scripts/locust python3 scripts/locust/generate_report.py "$csv" \
          2>&1 | tail -2 || true
    fi
done

_sep
echo " 全部实验完成  $(date '+%H:%M:%S')"
_sep
