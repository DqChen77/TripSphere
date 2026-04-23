#!/usr/bin/env bash
# Batch fault-injection demo for trip-itinerary-planner.
#
# Prerequisites:
#   1. Containers up via `task start` AFTER `export FAULT_INJECTION_ENABLED=true`.
#   2. Verify with: docker logs trip-itinerary-planner | grep "fault injection enabled"
#
# Each case fires a single curl, prints the latency it observed, and an
# experiment id you can search for in Tempo:
#
#   { resource.service.name = "trip-itinerary-planner"
#     && .experiment.id = "exp-XXX"
#     && .fault.injected = true }
#
# Destination is fixed to Shanghai per project convention.

set -uo pipefail

PLANNER="${PLANNER:-http://localhost:24215}"
USER_ID="${USER_ID:-42}"

# ── shared payload ─────────────────────────────────────────────────────────
read -r -d '' BODY <<'JSON' || true
{
  "destination": "Shanghai",
  "start_date": "2026-05-01",
  "end_date": "2026-05-03",
  "interests": ["culture"],
  "pace": "moderate"
}
JSON

# ── helpers ────────────────────────────────────────────────────────────────
run_case() {
    local id="$1"          # short id for logs
    local title="$2"       # human description
    local scenario="$3"    # x-fault-scenario header value
    local expect="$4"      # expected outcome hint
    local exp_id="exp-${id}-$(date +%s)"

    printf '\n────────────────────────────────────────────────────────────\n'
    printf '[%s] %s\n' "$id" "$title"
    printf '  scenario : %s\n' "$scenario"
    printf '  expect   : %s\n' "$expect"
    printf '  exp.id   : %s\n' "$exp_id"

    local start=$(date +%s%3N)
    local status
    status=$(
        curl -s -o /tmp/fault-demo.body -w '%{http_code}' \
            -X POST "${PLANNER}/api/v1/itineraries/plannings" \
            -H 'Content-Type: application/json' \
            -H "x-user-id: ${USER_ID}" \
            -H "x-experiment-id: ${exp_id}" \
            -H "x-fault-scenario: ${scenario}" \
            -d "${BODY}"
    )
    local end=$(date +%s%3N)
    local elapsed_ms=$((end - start))

    printf '  http     : %s   elapsed : %sms\n' "$status" "$elapsed_ms"

    if [[ "$status" =~ ^2 ]]; then
        # Pretty-print just the itinerary id and a hint of markdown length
        local saved_id
        saved_id=$(grep -oE '"id":"[a-f0-9]{24}"' /tmp/fault-demo.body | head -1 || true)
        local md_len
        md_len=$(grep -oE '"markdown_content":"[^"]*"' /tmp/fault-demo.body \
                 | head -1 | wc -c)
        printf '  saved    : %s   markdown_chars≈%s\n' "$saved_id" "$md_len"
    else
        printf '  body     : '
        head -c 300 /tmp/fault-demo.body
        printf '\n'
    fi
}

# ── Case matrix ────────────────────────────────────────────────────────────
# Layered roughly by primitive + chain:
#   F1 latency / F2 exception / F3 grpc_error / F5 LLM exception
#   F7 mutate response / combined scenarios

# === Chain A — REST 规划链 ===

# F1 latency: 高德地理编码 4s 延迟，整链仍成功（走 except 默认坐标）
run_case "A1-geo-lat" \
    "F1 高德地理编码延迟 4s，整链仍成功" \
    "tool.geocoding.latency=4000" \
    "HTTP 201 但 elapsed >= 4000ms; geocoding span 上 fault.outcome=delayed"

# F2 exception: 地理编码异常 -> 走 except 默认上海坐标
run_case "A2-geo-exc" \
    "F2 高德地理编码抛异常，整链仍成功（默认坐标兜底）" \
    "tool.geocoding.exception=ConnectionError,message=injected_amap_down" \
    "HTTP 201; nodes.py 日志 'Geocoding failed'"

# F2 exception: 景点 gRPC 异常 -> attractions=[]，触发幻觉路径
run_case "A3-attr-exc" \
    "F2 景点 gRPC 异常，进入空候选幻觉路径" \
    "rpc.attraction.GetAttractionsNearby.exception=RuntimeError,message=injected_attr_down" \
    "HTTP 201 但生成的 day_plans 几乎为空 / 内容空洞"

# F2 exception: 酒店 gRPC 异常 -> hotel_details=[]
run_case "A4-hotel-exc" \
    "F2 酒店 gRPC 异常，住宿活动消失" \
    "rpc.hotel.GetHotelsNearby.exception=RuntimeError,message=injected_hotel_down" \
    "HTTP 201; 行程没有 hotel_stay 类活动"

# F3 grpc_error: itinerary 持久化 UNAVAILABLE -> 502
run_case "A5-itin-unavail" \
    "F3 itinerary gRPC UNAVAILABLE，强一致性失败" \
    "rpc.itinerary.create.error=UNAVAILABLE,message=injected_itinerary_down" \
    "HTTP 502 'Failed to persist itinerary'"

# F3 grpc_error: itinerary 持久化 INTERNAL
run_case "A6-itin-internal" \
    "F3 itinerary gRPC INTERNAL，整链 502" \
    "rpc.itinerary.create.error=INTERNAL,message=injected_itinerary_internal" \
    "HTTP 502"

# F2 LLM exception: 结构化规划 LLM 异常 -> 空 day_plans + 通用 highlights
run_case "A7-llm-struct-exc" \
    "F5/F2 结构化 LLM 异常，行程骨架空" \
    "llm.research_and_plan.structured.exception=RuntimeError,message=injected_llm_down" \
    "HTTP 201 但 day_plans 极少；nodes.py 日志 'LLM planning failed'"

# F1 LLM latency: Markdown LLM 慢 5s
run_case "A8-llm-md-lat" \
    "F1 Markdown LLM 延迟 5s" \
    "llm.generate_markdown.latency=5000" \
    "HTTP 201; elapsed 比基线多 ~5s"

# F2 LLM exception: Markdown 失败 -> _build_fallback_markdown 兜底
run_case "A9-llm-md-exc" \
    "F2 Markdown LLM 异常，markdown 走本地兜底" \
    "llm.generate_markdown.exception=RuntimeError,message=injected_markdown_down" \
    "HTTP 201; markdown_content 为简单的 fallback 拼装"

# F7 mutate: 把景点截断到 1 条 -> LLM 几乎没有素材
run_case "A10-attr-trunc" \
    "F7 景点结果截断到 1 条，验证候选不足下的生成质量" \
    "tool.attractions.response.mutate=truncate,field=attractions,n=1" \
    "HTTP 201; day_plans 内容很单薄"

# F7 mutate: 把酒店截断到 0 条
run_case "A11-hotel-blank" \
    "F7 酒店列表清空，验证住宿缺失" \
    "tool.hotel.response.mutate=blank,field=hotels" \
    "HTTP 201; 行程没有 hotel_stay 活动"

# 组合: 高德延迟 + 景点异常，验证多兜底叠加
run_case "A12-combo-cascade" \
    "组合: 高德延迟 4s + 景点 gRPC 异常 (兜底链路叠加)" \
    "tool.geocoding.latency=4000;rpc.attraction.GetAttractionsNearby.exception=RuntimeError" \
    "HTTP 201; elapsed >= 4000ms 且 day_plans 空洞"

# 组合: itinerary 失败 + Markdown 异常（验证短路：502 不会跑到 markdown）
run_case "A13-combo-fail-fast" \
    "组合: itinerary UNAVAILABLE + Markdown 异常（验证短路）" \
    "rpc.itinerary.create.error=UNAVAILABLE;llm.generate_markdown.exception=RuntimeError" \
    "HTTP 502; markdown 异常因短路而未必触发"

# 概率门: 50% 概率注入延迟 — 多跑几次看分布
run_case "A14-prob-half" \
    "概率门 50%: 跑一次（重复跑可见命中分布）" \
    "tool.geocoding.latency=3000,probability=0.5" \
    "约一半请求 elapsed ~3000ms，另一半秒回"

printf '\n────────────────────────────────────────────────────────────\n'
printf 'Done. 在 Grafana / Tempo 用以下 TraceQL 查询任一 exp.id:\n'
printf '  { resource.service.name = "trip-itinerary-planner"\n'
printf '    && .experiment.id = "<上面任一 exp.id>"\n'
printf '    && .fault.injected = true }\n'
