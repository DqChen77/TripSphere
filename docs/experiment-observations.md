# 实验过程观测记录

> 本文件记录压测过程中发现的有价值现象，供论文 Case Study 章节引用。
> 时间线：2026-05-11

---

## OBS-01　Chain B baseline 大量降级（并发写同一行程引发状态污染）

### 现象

Chain B baseline-low（无故障注入，3 用户，2 分钟）中，33 条请求有 16 条被判定为降级（48.5%），降级信号为：

| 信号 | 次数 |
|------|------|
| `no_tool_calls` + `no_state_update` | 11 |
| `no_state_update` + `no_response_text` | 5 |

涉及 turn 类型：`plan_new_day`（5 次）、`regenerate_day`（6 次）、`query_itinerary`（5 次）。

### 根本原因

**服务端 LangGraph 对话历史出现"悬空 tool_call"**。

容器日志显示，整个测试窗口内所有失败请求均报同一错误：

```
openai.BadRequestError: 400 — An assistant message with 'tool_calls' must be
followed by tool messages responding to each 'tool_call_id'.
The following tool_call_ids did not have response messages: call_9gbQKypttUqdrCDW2M3HA67j
(messages.[29].role)
```

复现路径：

1. 测试脚本将同一个 `itinerary_id` 分配给所有并发 locust 用户
2. 多用户并发向同一行程发出 `plan_new_day`/`regenerate_day` 请求
3. 某次请求执行中途（agent node 调用工具后、tool 响应写回前）发生写冲突或异常
4. **assistant 消息（含 tool_call 声明）已持久化，tool 响应消息未能写入**
5. 此后所有加载该对话历史的请求均被 LLM API 拒绝（400），形成**永久性状态污染**

### 影响范围

- 一次污染导致该行程的全部后续 turn 不可用
- 由于测试中所有用户共享同一行程，污染几乎立即扩散至整个实验窗口
- 该 bug 与故障注入无关，是 agent 在并发场景下缺乏状态写事务保护的设计缺陷

### 与真实生产的对比

| 维度 | 测试场景 | 生产场景 |
|------|---------|---------|
| 并发写同一行程 | 必然（所有用户共享单 ID）| 极罕见（仅家庭共享场景） |
| 触发概率 | 高（100% 复现） | 低 |
| 影响范围 | 全部后续请求 | 仅特定行程会话 |

### 论文价值

- 证明 **LangGraph 有状态 agent 在并发场景下缺乏幂等写保护**
- 属于"非注入故障由测试设计暴露"的典型案例，可作为 Case Study 素材
- 说明即便 baseline（无故障注入）也可能出现严重降级，因此实验基线测量需要隔离行程状态

---

## OBS-02　smoke_test 脚本设计缺陷及修复

记录压测工具本身的三处 bug，供"实验设计"章节说明工程实现细节。

### 缺陷 1：Chain B itinerary ID 解析时机过早

**现象**：首次运行时 Chain B 被跳过（`CHAIN_B_ITINERARY_ID` 为空）。

**原因**：ID 自动发现逻辑在 Chain A 运行**之前**执行，此时 quality.csv 尚未生成。

**修复**：将 ID 解析移至 `_run_and_report "a"` 完成之后。

### 缺陷 2：locust 请求失败导致整个脚本提前中止

**现象**：fault-high Chain A 运行完后脚本直接退出，Chain B/C 未执行（exit code 1）。

**原因**：locust 遇到 HTTP 失败时以非零码退出 → `run-matrix.sh`（`set -e`）提前中止 → `smoke_test.sh`（`set -e`）接到非零码也中止。fault-high 天然存在请求失败，而 baseline 因无失败未触发此路径。

**修复**：两处 `eval` 后加 `|| true`，失败数据已写入 quality.csv，不依赖退出码。

### 缺陷 3：fault-high 场景名使用了 `single` 模式不存在的场景

**现象**：`chaina_final` 在 `mode=single` 下不存在。

**原因**：`chaina_final`/`chainb_final`/`chainc_final` 属于 `combo` 模式场景。

**修复**：`_run_fault_high` 调用时注入 `SCENARIO_MODE=combo`。

---

## OBS-03　Chain B 并发测试设计改进

### 问题

OBS-01 揭示了测试设计的根本缺陷：所有 locust 用户共享同一 `CHAIN_B_ITINERARY_ID`，与真实用户模型（每人独立行程）不符，且人为放大并发写冲突。

### 改进方案

引入 **itinerary ID 池**：

- `CHAIN_B_ITINERARY_ID` 支持逗号分隔多个 ID
- smoke_test.sh 自动从 Chain A quality.csv 提取所有已生成行程 ID（最多 10 个）
- 每个 locust 用户在 `on_start` 时轮流（round-robin）绑定独立 ID

**效果**：N 个并发用户操作 N 个不同行程，消除人为写冲突，baseline 数据可信。

### 两种测试模式的语义区分

| 传参方式 | 语义 |
|---------|------|
| `CHAIN_B_ITINERARY_ID=id1,id2,id3`（多个）| 真实负载模型，用于 baseline 与 fault 对比 |
| `CHAIN_B_ITINERARY_ID=id1`（单个）| 并发共享行程压力测试，用于复现 OBS-01 类 bug |

---

## OBS-04　fault-high 初步结果（Chain A）

实验参数：`chaina_final` combo 场景，10 用户，2 分钟，`fault-high` 象限。

| 指标 | 数值 |
|------|------|
| 总请求数 | 22 |
| HTTP 502（持久化失败） | 10（45.5%）|
| 2xx 中降级（geocoding_fallback） | 2（16.7%）|
| p95 延迟 | 63,000 ms |
| 注入 DSL | `geocoding.latency=5s,p=0.5; attraction.exception,p=0.4; itinerary.persist.UNAVAILABLE,p=0.2` |

**观察**：`rpc.itinerary.create` 注入概率 0.2 + 10 用户并发，实际 502 率远超预期（45.5% vs ~20%），推测高并发下故障命中叠加效应显著。这与 baseline-low 下的 0% 502 形成鲜明对比，是实验故障检测能力的直接证据。
