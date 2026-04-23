# 故障注入框架快速使用指南

> 本文配套 [`docs/langgraph-fault-injection-report.md`](langgraph-fault-injection-report.md) 的设计文档与 `docs/aim.md` 北极星。
> 落地代码位于：
> - `trip-itinerary-planner/src/itinerary_planner/observability/fault.py`
> - `trip-chat-service/src/chat/observability/fault.py`

## 0. 快速结论

- 默认关闭，零成本。`FAULT_INJECTION_ENABLED` 未设置时，所有装饰器都走早退分支。
- 一旦开启，单条 trace、单个 Agent turn、单个工具都可以被精确注入；注入证据通过 OTel Span 直接打到 Tempo。
- 故障表达力：延迟 / 异常 / gRPC 错误码 / 响应篡改 / 状态清空 / 路由扰动 / Agent drop。
- 故障来源：启动期 env / JSON 文件，或单条请求的 `x-fault-scenario` HTTP 头部。

## 1. 启用方式

> **重要**：服务跑在 `task start`（即 `docker compose up`）拉起的容器里。
> 容器只能看到 `docker-compose.yaml` `environment:` 段里**显式列出**的变量，
> 宿主机 `export` 的环境变量不会自动透传。本仓库已在
> `trip-itinerary-planner` 与 `trip-chat-service` 两个服务的 compose 段加了
> `FAULT_INJECTION_ENABLED` / `FAULT_INJECTION_SCENARIO` / `FAULT_INJECTION_FILE`
> 三个透传槽位（默认关闭），所以 host 上的 export 才有效。

### 1.1 全局开关

```bash
# 默认 false；任何一种生效写法均可
export FAULT_INJECTION_ENABLED=true   # 也接受 1 / yes / on
task start                            # 必须重启容器才会加载新的 env
```

容器内的 Python 进程在 `lifespan` 启动期会自动读一次（见 `asgi.py` 中的
`FaultRegistry.instance().bootstrap()`）；不重启就改 env 不会生效。

### 1.2 启动期常驻故障（可选）

```bash
# 直接走环境变量（适合临时实验）
export FAULT_INJECTION_SCENARIO="tool.geocoding.latency=8000;rpc.itinerary.create.error=UNAVAILABLE"

# 或者通过 JSON 文件（适合多实例同步、CI / GitOps）
export FAULT_INJECTION_FILE=/etc/tripsphere/faults.json
```

JSON 文件结构（数组）：

```json
[
  {
    "target": "tool.geocoding",
    "primitive": "latency",
    "params": { "value": 6000, "jitter": 200 },
    "probability": 1.0,
    "experiment_id": "exp-2026-04-tool-timeout-01"
  },
  {
    "target": "agent.order_assistant",
    "primitive": "drop"
  }
]
```

文件被破坏 / 字段缺失时框架不会崩溃，只会在日志里给出 warning。

### 1.3 单请求注入（推荐用于交互式调试 / 答辩演示）

只要 **`FAULT_INJECTION_ENABLED=true` 已经在容器内生效**（通过 §1.1 的方式），
后续就不需要 `FAULT_INJECTION_SCENARIO` 这个常驻配置——任何一条请求自带 header
即可命中目标，**且不需要重启容器**：

```bash
EXP=exp-demo-$(date +%s)

curl -i -X POST http://localhost:24215/api/v1/itineraries/plannings \
  -H 'Content-Type: application/json' \
  -H 'x-user-id: 42' \
  -H "x-experiment-id: ${EXP}" \
  -H 'x-fault-scenario: tool.geocoding.latency=4000' \
  -d '{
    "destination": "Shanghai",
    "start_date": "2026-05-01",
    "end_date": "2026-05-03",
    "interests": ["culture"],
    "pace": "moderate"
  }'

echo "experiment id = ${EXP}"
```

进程内中间件（`trip-itinerary-planner` 与 `trip-chat-service` 的 `chat_entry_observability_middleware`）会把头部解析进 ContextVar，整条调用链上的工具 / RPC / LLM 都能命中。

## 2. DSL 语法

```
<target>.<primitive>=<arg1>[,<arg2>][,key=val,...]
```

多条故障用 `;` 拼接：

```
tool.geocoding.latency=4000,jitter=200;rpc.itinerary.create.error=UNAVAILABLE;state.itinerary.clear=true
```

### 2.1 目前支持的 primitive

| primitive    | 适用 target 类型 | 含义 | 关键参数 |
|--------------|----------------|------|----------|
| `latency`    | tool / rpc / llm | 进入业务调用前 sleep | `value=ms`, `jitter=ms` |
| `exception`  | tool / rpc / llm | 调用前抛 Python 异常 | `value=ClassName`, `message=...` |
| `error`      | rpc | 抛 `grpc.aio.AioRpcError` | `value=UNAVAILABLE\|...`, `message=...` |
| `mutate`     | `<target>.response` | 返回值篡改 | `value=truncate\|blank\|set\|noop`, `field=...`, `n=...`, `to=...` |
| `clear`      | state | 状态字段清空 | （无） |
| `force_route`| route | 强制路由分支 | `value=__end__\|tools` |
| `drop`       | agent | 模拟远端 Agent 消失 | （无） |

### 2.2 可选通用参数

- `probability=0.0~1.0`：按概率触发，缺省 1.0。
- `experiment_id=...`：覆盖实验主键（默认走 `x-experiment-id` 头部）。

## 3. 已接入的注入点（target 速查表）

### 链路 A — REST 规划链（`trip-itinerary-planner`）

| target | 代码位置 | 说明 |
|--------|----------|------|
| `tool.geocoding`              | `tools/geocoding.py`           | 高德 HTTP 调用前 |
| `tool.geocoding.response`     | 同上                           | 高德响应篡改 |
| `rpc.attraction.GetAttractionsNearby` | `tools/attractions.py` | 景点 gRPC 调用前 |
| `tool.attractions.response`   | 同上                           | 景点列表篡改（`field=attractions, value=truncate`）|
| `rpc.hotel.GetHotelsNearby`   | `tools/hotel.py`               | 酒店 gRPC 调用前 |
| `tool.hotel.response`         | 同上                           | 酒店列表篡改 |
| `llm.research_and_plan.structured` | `agent/nodes.py`          | 结构化 LLM 调用前 |
| `llm.generate_markdown`       | `agent/nodes.py`               | Markdown LLM 调用前 |
| `rpc.itinerary.create`        | `grpc/clients/itinerary.py`    | 持久化 gRPC 调用前 |
| `rpc.itinerary.get / list / replace / delete` | 同上          | 其余 itinerary RPC |

### 链路 B — CopilotKit ReAct（`trip-itinerary-planner`）

| target | 代码位置 | 说明 |
|--------|----------|------|
| `llm.chat_agent`              | `agent/chat_agent.py`          | ReAct 主 LLM 调用前 |
| `state.itinerary`             | 同上                           | reducer 写入前清空 |
| `state.pending_day_plan`      | `tools/itinerary.py`           | `plan_new_day` 草案写回前清空 |
| `route.should_continue`       | `agent/chat_agent.py`          | 强制 `__end__` 或 `tools` |

### 链路 C — Chat → A2A → Order（`trip-chat-service`）

| target | 代码位置 | 说明 |
|--------|----------|------|
| `agent.<remote_name>` 例如 `agent.order_assistant` | `chat/agent/remote_agent.py` | 远端 Agent drop |
| `a2a.trace` | `chat/agent/remote_agent.py` | A2A trace 链断开 |

## 3.4 一键批量演示脚本

仓库自带 `scripts/fault-demo.sh`，覆盖链路 A 上 14 个故障组合（latency / exception / grpc_error / mutate / 概率门 / 组合场景），目的地固定为上海。

```bash
# 前提：FAULT_INJECTION_ENABLED 已经在容器内生效
docker logs trip-itinerary-planner | grep "fault injection enabled"

# 跑全部用例
bash scripts/fault-demo.sh

# 也可以指定其它 endpoint / 用户
PLANNER=http://10.0.0.5:24215 USER_ID=99 bash scripts/fault-demo.sh
```

每个用例会打印：

- `exp.id`（可直接拿去 Tempo 查 trace）
- `http`（HTTP 状态码）
- `elapsed`（实测耗时，与延迟注入对照）
- `expect`（设计预期）

---

## 4. 端到端验证（手工）

1. 启动 `trip-itinerary-planner` 与依赖（OTel Collector / Tempo）；保留 `FAULT_INJECTION_ENABLED=true`。
2. 用 §1.3 的 curl 命令发请求。
3. 在 Grafana → Tempo 中按 `experiment.id="exp-quickstart-01"` 过滤，应能看到一条 trace；点开后 `rpc.AmapGeocoding.v3.geocode.geo` Span 的属性应包含：
   - `fault.injected = true`
   - `fault.target = tool.geocoding`
   - `fault.primitive = latency`
   - `fault.outcome = delayed`
   - `fault.params.value = 3000`
4. 整条 trace 的端到端 duration 比基线 ≥ 3 秒。

## 5. 实验记录建议

每个实验沿用报告 §5.3 的 YAML 模板，关键字段：

- `experiment.id`：与 `x-experiment-id` 一致。
- `fault.scenario`：原样保留 DSL 字符串。
- `metrics.before / during / after`：从 Prometheus / Tempo 取窗口统计。
- `trace_evidence`：列出至少 1 条命中 `fault.injected=true` 的 trace_id。
- `conclusion`：判定假设是否成立 + 后续改进点。

## 6. 关闭与回滚

```bash
unset FAULT_INJECTION_ENABLED
```

或通过滚动重启使 `lifespan` 重新执行 `bootstrap()`，框架立即回到零成本路径。

## 7. OTel Collector 可选补强

如需把 `fault.*` 维度提升为 metrics（便于 Grafana 看板），可以在 `infra/otel-collector/config.yaml` 中追加 `connector/spanmetrics`，把 `fault.injected=true` 转化为 counter。设计报告 §5.5 给出详细建议；本轮代码不修改 Collector 配置，需要时按上述指引手动加。
