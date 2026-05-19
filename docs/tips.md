1. chain-a baseline中的degradation是AI Provider方的内容审查导致的失败
2. chain-c baseline中的degradation是order-assistant 向 order-service 发起创建订单的 gRPC 请求时，payload 中 items 列表为空（草单虽然创建成功 draft=True，但 submit 阶段传入的 items 为空），server-side validation 拒绝了请求。agent 随即回复了错误提示文本，但这属于真实的应用层 bug


