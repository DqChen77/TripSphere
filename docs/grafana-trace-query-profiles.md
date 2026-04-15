# Grafana Trace Query Profiles

## 目的
- 默认视图优先看业务链路，不被 `chat_db.find` 与通用 `POST / http send` 噪声淹没。
- 需要排查网络或数据库细节时，再进入下钻视图。

## Profile A: 主视图（业务链路）
用于故障注入复盘的默认查询，优先展示带业务语义的 span。

### A1 按实验聚合（推荐）
```traceql
{ .experiment.id = "$experiment_id" && (.tool.name != nil || .rpc.service != nil || .chat.entry = "true") }
```

### A2 指定服务 + 业务 span
```traceql
{ resource.service.name =~ "trip-(chat-service|order-assistant)" && (.tool.name != nil || .rpc.service != nil || .chat.entry = "true") }
```

## Profile B: 下钻视图（HTTP）
用于定位 `POST / http send` 相关网络问题，不作为默认视图。

### B1 仅看 POST 下游调用
```traceql
{ .experiment.id = "$experiment_id" && (.http.request.method = "POST" || .http.method = "POST") }
```

### B2 指向模型网关（示例）
```traceql
{ .experiment.id = "$experiment_id" && (.http.request.method = "POST" || .http.method = "POST") && (.server.address =~ ".*higress.*" || .net.peer.name =~ ".*higress.*") }
```

## Profile C: 下钻视图（Mongo）
用于确认 Mongo 读路径是否仍异常放大（Collector 已默认过滤 `chat_db.find`）。

### C1 Mongo 查询残余检查
```traceql
{ resource.service.name = "trip-chat-service" && .db.system = "mongodb" }
```

### C2 指定 find 检查（兼容旧/新语义字段）
```traceql
{ resource.service.name = "trip-chat-service" && ((.db.operation = "find" || .db.operation.name = "find") && (.db.name = "chat_db" || .db.namespace = "chat_db")) }
```

## 使用建议
- 默认看 Profile A，判断故障是否发生在 Tool/RPC 业务阶段。
- 业务链路异常后，再切到 Profile B 或 C 下钻细节。
- 所有看板统一保留 `experiment.id` 变量，确保一次实验可跨服务聚合。
