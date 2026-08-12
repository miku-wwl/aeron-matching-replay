# 第 12 课：当前 Demo 与生产系统的边界

## 当前版本明确没有什么

- 没有 Postgres 和最终状态查询。
- 没有 Transactional Outbox。
- 没有 Lease、Heartbeat、Fencing Token、Checkpoint。
- 没有 Retry、DLQ 和自定义业务 Metrics。
- RabbitMQ 仍是本地 Docker 单节点，k3d 也只是本机集群。

这些删减是为了让初学者先看清 KEDA 的事件驱动扩缩容，不代表生产系统可以照搬。

## 直接发布的可靠性窗口

当前流程是：

```text
HTTP 请求 → API 内存中生成 JobEvent → RabbitMQ publish
```

如果 API 在 publish 前崩溃，调用方只能根据 HTTP 错误重试；系统没有数据库记录帮助恢复。若业务必须保证“状态写入”和“消息发送”一致，就需要重新引入数据库和 Outbox，或采用其他可靠事件总线方案。

## 生产化方向

按需求逐项增加：

1. 需要查询和审计：加入业务数据库与状态模型。
2. 需要定时执行：加入调度器或延迟消息机制。
3. 需要可靠投递：数据库事务 + Outbox + 发布重试。
4. 需要失败恢复：幂等键、Retry、DLQ 和人工补偿。
5. 需要多节点高可用：托管 RabbitMQ、Kubernetes 多节点、PDB、拓扑分布和外部 Secret 管理。
6. 需要运维闭环：Prometheus/Grafana、日志、告警、追踪和容量基线。

## 练习

1. Outbox 解决的是直接发布链路中的哪一个时间窗口？
2. 为什么加入 Postgres 后仍然不能自动保证消息一定送达？
3. 哪些能力是业务需求驱动的，哪些只是平台高可用能力？
