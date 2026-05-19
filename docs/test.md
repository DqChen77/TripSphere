# TripSphere 最终实验测试方案

> 目标：三条链路各跑一个 2×2 矩阵（baseline-low / baseline-high / fault-low / fault-high），
> 每条链路使用一个 combo 故障场景，概率在真实故障率基础上适度夸大（约 3–5×），
> 保证在实验窗口内有足够的故障命中样本，同时不让系统完全不可用。

---

## 故障选型总览

| 链路 | combo 场景名 | 注入点数 | 故障层覆盖 | 降级检测主信号 |
|------|------------|---------|-----------|--------------|
| A — REST 规划 | `chaina_final` | 3 | 外部 HTTP + gRPC 数据 + gRPC 持久化 | failure_rate + p95 latency + activity_count |
| B — ReAct 对话 | `chainb_final` | 3 | LLM + 状态 + 工具协调 | failure_rate + chat.turn.outcome + state_cleared |
| C — A2A 跨服务 | `chainc_final` | 4 | 可观测性 + LLM + 工具 gRPC + 状态 | run_error + no_order_draft + no_order_submit + trace 完整率 |

---

## Chain A — REST 规划链

### combo 场景：`chaina_final`

> 覆盖三个不同层次：外部 HTTP（高德）、gRPC 数据服务（景点）、gRPC 持久化（行程写库）。
> 三个故障路径相互独立，可分别在 Trace 里归因，互不掩盖。

| 注入点 | primitive | 参数 | 概率 | 真实估计 | 说明 |
|--------|-----------|------|------|---------|------|
| `tool.geocoding` | latency | 5000ms | 0.5 | ~10% | 高德 API 慢响应，触发 default_coords 降级 |
| `rpc.attraction.GetAttractionsNearby` | exception | RuntimeError | 0.4 | ~8% | gRPC 景点服务抖动，候选为空，内容质量退化 |
| `rpc.itinerary.create` | error | UNAVAILABLE | 0.2 | ~2% | 持久化失败，直接返回 HTTP 502 |

**DSL：**
```
tool.geocoding.latency=5000,probability=0.5;rpc.attraction.GetAttractionsNearby.exception=RuntimeError,message=injected_attr_down,probability=0.4;rpc.itinerary.create.error=UNAVAILABLE,message=injected_persist_down,probability=0.2
```

### 降级检测

| 信号 | 来源 | 预期（baseline → fault） |
|------|------|------------------------|
| `failure_rate` | Locust stats_stats.csv | ~0% → ~20%（持久化失败） |
| `p95_ms` | Locust | +5000ms 区间（geocoding 影响 50% 请求） |
| `itinerary_saved_rate` | quality.csv | ~100% → ~80% |
| `activity_count` median | quality.csv | 下降（景点候选 40% 为空） |
| `markdown_length` median | quality.csv | 下降（内容退化） |
| Trace `fault.injected=true` | Tempo TraceQL | 三类 Span 上均有分布 |

### 负载参数

| 象限 | users | spawn_rate | duration |
|------|-------|-----------|----------|
| baseline-low | 5 | 1 | 10m |
| fault-low | 5 | 1 | 10m |
| baseline-high | 20 | 3 | 10m |
| fault-high | 20 | 3 | 10m |

### 运行命令

```bash
# 前置：确认 fault injection 已开启
export FAULT_INJECTION_ENABLED=true

# Q1 baseline-low
CHAIN=a bash scripts/locust/run-matrix.sh baseline-low "" 5 1 10m

# Q2 fault-low
CHAIN=a bash scripts/locust/run-matrix.sh fault-low chaina_final 5 1 10m

# Q3 baseline-high
CHAIN=a bash scripts/locust/run-matrix.sh baseline-high "" 20 3 10m

# Q4 fault-high
CHAIN=a SCENARIO_MODE=combo bash scripts/locust/run-matrix.sh fault-high chaina_final 20 3 10m
```

---

## Chain B — ReAct 对话链

### combo 场景：`chainb_final`

> 三个故障分属不同层：LLM 层（直接中断 turn）、State 层（链路 B 独有的状态篡改）、
> 工具协调层（plan_new_day 草案丢失 → add_day 拒绝）。
> 选型目的是展示 ReAct 循环在多点退化下的行为，同时覆盖论文中状态/记忆篡改章节。

| 注入点 | primitive | 参数 | 概率 | 真实估计 | 说明 |
|--------|-----------|------|------|---------|------|
| `llm.chat_agent` | exception | RuntimeError | 0.4 | ~5% | ReAct 主 LLM 调用失败，turn 直接中断 |
| `state.itinerary` | clear | true | 0.25 | ~1% | 状态层篡改：_keep_itinerary reducer 写入前清空 |
| `state.pending_day_plan` | clear | true | 0.35 | ~3% | 草案丢失：plan_new_day → add_day 协调断裂 |

**DSL：**
```
llm.chat_agent.exception=RuntimeError,message=injected_chat_llm_down,probability=0.4;state.itinerary.clear=true,probability=0.25;state.pending_day_plan.clear=true,probability=0.35
```

### 降级检测

| 信号 | 来源 | 预期（baseline → fault） |
|------|------|------------------------|
| `failure_rate` | Locust stats_stats.csv | ~0% → ~40%（LLM 异常直接导致 turn 失败） |
| `degradation_detected` | quality.csv | 升高（state 清空触发 agent 回复异常） |
| Trace `chat.turn.outcome=error` | Tempo | fault 组显著高于 baseline |
| Trace `fault.target=state.itinerary` | Tempo | 约 25% spans 出现 state_cleared |
| Trace `tool.add_day fallback_reason=missing_pending_day_plan` | Tempo | 约 35% plan_new_day 后续 add_day 触发拒绝 |

### 负载参数

> Chain B 是 SSE 长连接 chat turn，并发不宜过高，LLM token 开销大。

| 象限 | users | spawn_rate | duration |
|------|-------|-----------|----------|
| baseline-low | 3 | 1 | 10m |
| fault-low | 3 | 1 | 10m |
| baseline-high | 10 | 2 | 10m |
| fault-high | 10 | 2 | 10m |

### 运行命令

```bash
# Q1 baseline-low
CHAIN=b bash scripts/locust/run-matrix.sh baseline-low "" 3 1 10m

# Q2 fault-low
CHAIN=b SCENARIO_MODE=combo bash scripts/locust/run-matrix.sh fault-low chainb_final 3 1 10m

# Q3 baseline-high
CHAIN=b bash scripts/locust/run-matrix.sh baseline-high "" 10 2 10m

# Q4 fault-high
CHAIN=b SCENARIO_MODE=combo bash scripts/locust/run-matrix.sh fault-high chainb_final 10 2 10m
```

---

## Chain C — A2A 跨服务链

### combo 场景：`chainc_final`

> 四个故障覆盖链路 C 的四个独立层次：
> - `a2a.trace.drop`：**可观测性层**，W3C trace 头被剥离，断链概率 30%；
> - `llm.order_assistant.exception`：**LLM 层**，order_assistant 主 LLM 调用失败，40% 概率触发 RUN_ERROR；
> - `rpc.product.GetSkuById.error`：**工具 gRPC 层**，商品查询 UNAVAILABLE，30% 概率导致草单加单失败；
> - `state.order_draft.clear`：**状态层**，草单写入前被清空，20% 概率导致后续加单/提交找不到草单。
>
> 组合语义：多层间歇退化并存——部分请求 LLM 直接失败，部分因商品或状态问题无法完成草单，
> 同时 Trace 有 30% 断链，模拟真实多维度故障下可靠性与可观测性的双重挑战。

| 注入点 | primitive | 参数 | 概率 | 配置方式 | 说明 |
|--------|-----------|------|------|---------|------|
| `a2a.trace` | drop | true | 0.3 | 请求头 per-request | A2A Trace 头剥离，30% 链路断链 |
| `llm.order_assistant` | exception | RuntimeError | 0.4 | 请求头 per-request | order_assistant LLM 调用失败 |
| `rpc.product.GetSkuById` | error | UNAVAILABLE | 0.3 | 请求头 per-request | 商品 SKU 查询失败，草单加单路径中断 |
| `state.order_draft` | clear | true | 0.2 | 请求头 per-request | 草单创建时被清空，后续提交找不到草单 |

**per-request DSL（Locust 发送的 x-fault-scenario 头）：**
```
a2a.trace.drop=true,probability=0.3;llm.order_assistant.exception=RuntimeError,message=injected_order_llm_down,probability=0.4;rpc.product.GetSkuById.error=UNAVAILABLE,message=injected_product_down,probability=0.3;state.order_draft.clear=true,probability=0.2
```

> ⚠️ 本场景无需 `agent.order_assistant.drop` 环境变量，服务正常启动即可。
> 若要额外验证子 Agent 完全不可用，使用独立的 `order_cascade` 单场景。

### 降级检测

| 信号 | 来源 | 预期（baseline → fault） |
|------|------|------------------------|
| `run_error` | quality.csv | ~0% → ~40%（LLM 异常） |
| `no_order_draft` | quality.csv | ~0% → ~50%（商品失败@30% + 草单清空@20%） |
| `no_order_submit` | quality.csv | ~0% → 上升（草单失败导致提交无法完成） |
| `no_delegation` | quality.csv | 维持低水平（子 Agent 正常启动） |
| `a2a_trace_drop_declared` | quality.csv | fault 组约 30% 请求可见 |
| Trace 跨服务完整率 | Tempo | ~100% → ~70%（trace 断链） |
| Span `fault.injected=true` | Tempo | 四类 Span 均有分布 |

### 负载参数

> Chain C 跨 3 个 Python 服务 + Java gRPC，不宜高并发。

| 象限 | users | spawn_rate | duration |
|------|-------|-----------|----------|
| baseline-low | 3 | 1 | 10m |
| fault-low | 3 | 1 | 10m |
| baseline-high | 8 | 2 | 10m |
| fault-high | 8 | 2 | 10m |

### 运行命令

```bash
# 前置：确认 fault injection 已开启（无需额外 FAULT_INJECTION_SCENARIO）
export FAULT_INJECTION_ENABLED=true

# Q1 baseline-low
CHAIN=c bash scripts/locust/run-matrix.sh baseline-low "" 3 1 10m

# Q2 fault-low
CHAIN=c SCENARIO_MODE=combo bash scripts/locust/run-matrix.sh fault-low chainc_final 3 1 10m

# Q3 baseline-high
CHAIN=c bash scripts/locust/run-matrix.sh baseline-high "" 8 2 10m

# Q4 fault-high
CHAIN=c SCENARIO_MODE=combo bash scripts/locust/run-matrix.sh fault-high chainc_final 8 2 10m
```

---

## fault_scenarios.yaml 新增条目

在 `scripts/locust/fault_scenarios.yaml` 的 `combo` 节追加：

```yaml
  chaina_final:
    description: >
      Chain A 最终实验：外部 HTTP 高延迟 50% + 景点 gRPC 异常 40% + 持久化 UNAVAILABLE 20%，
      覆盖三层独立故障路径，验证降级路径完整性与持久化失败用户面表现。
    faults:
      - target: tool.geocoding
        primitive: latency
        value: 5000
        probability: 0.5
      - target: rpc.attraction.GetAttractionsNearby
        primitive: exception
        value: RuntimeError
        message: injected_attr_down
        probability: 0.4
      - target: rpc.itinerary.create
        primitive: error
        value: UNAVAILABLE
        message: injected_persist_down
        probability: 0.2

  chainb_final:
    description: >
      Chain B 最终实验：ReAct LLM 异常 40% + state.itinerary 清空 25% + pending_day_plan 清空 35%，
      覆盖 LLM 层、状态层、工具协调层三类链路 B 特有故障，验证 ReAct 多点退化行为。
    faults:
      - target: llm.chat_agent
        primitive: exception
        value: RuntimeError
        message: injected_chat_llm_down
        probability: 0.4
      - target: state.itinerary
        primitive: clear
        value: "true"
        probability: 0.25
      - target: state.pending_day_plan
        primitive: clear
        value: "true"
        probability: 0.35

  chainc_final:
    description: >
      Chain C 最终实验：A2A trace 断链 30% + order_assistant LLM 异常 40% + 商品 SKU gRPC 失败 30% + 草单状态清空 20%，
      覆盖可观测性层、LLM 层、工具 gRPC 层、状态层四类链路 C 故障，验证多层间歇退化下的业务质量与可观测性退化。
    faults:
      - target: a2a.trace
        primitive: drop
        value: "true"
        probability: 0.3
      - target: llm.order_assistant
        primitive: exception
        value: RuntimeError
        message: injected_order_llm_down
        probability: 0.4
      - target: rpc.product.GetSkuById
        primitive: error
        value: UNAVAILABLE
        message: injected_product_down
        probability: 0.3
      - target: state.order_draft
        primitive: clear
        value: "true"
        probability: 0.2
```

---

## 执行顺序建议

```
Chain A（4 象限）  →  Chain B（4 象限）  →  Chain C（4 象限）
```

- 每个象限结束后保存 `artifacts/locust/{experiment_id}/`，记录 Prometheus 时间窗口。
- Chain B / Chain C 象限之间预留 5 分钟恢复窗口，观察指标是否回落。
- Chain C 的 baseline ↔ fault 切换需要重启 trip-chat-service，baseline 两个象限可以连续跑，fault 两个象限连续跑，各只需重启一次。

## Hook 落地状态（已确认）

| 故障目标 | 代码位置 | 状态 |
|---------|---------|------|
| `llm.chat_agent` | `chat_agent.py` `invoke_with_fault("llm.chat_agent", model_with_tools, ...)` | ✅ |
| `state.itinerary` | `chat_agent.py` `should_clear_state("state.itinerary")` in `_keep_itinerary` | ✅ |
| `state.pending_day_plan` | `tools/itinerary.py` `should_clear_state("state.pending_day_plan")` in `plan_new_day` | ✅ |
| `route.should_continue` | `chat_agent.py` `force_route_decision("route.should_continue", ...)` | ✅ |
| `a2a.trace` | `remote_agent.py` `should_drop("a2a.trace", headers=headers)` | ✅ |
| `agent.order_assistant` | `remote_agent.py` `should_drop(f"agent.{agent_name}")` | ✅（启动时） |
| `tool.geocoding` | `tools/geocoding.py` `inject_fault("tool.geocoding")` | ✅ |
| `rpc.attraction.GetAttractionsNearby` | `tools/attractions.py` `inject_fault(...)` | ✅ |
| `rpc.itinerary.create` | `grpc/clients/itinerary.py` `inject_fault("rpc.itinerary.create")` | ✅ |
| `llm.order_assistant` | `order_assistant/agent.py` `before_model_callback inject_fault("llm.order_assistant")` | ✅ |
| `rpc.product.GetSkuById` | `order_assistant/tools/product.py`, `tools/order_draft.py` `inject_fault(...)` | ✅ |
| `rpc.order.CreateOrder` | `order_assistant/tools/order_draft.py` `inject_fault("rpc.order.CreateOrder")` | ✅ |
| `state.order_draft` | `order_assistant/tools/order_draft.py` `should_clear_state("state.order_draft")` | ✅ |
