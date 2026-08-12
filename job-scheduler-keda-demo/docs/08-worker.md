# 第 08 课：Worker

## 消费逻辑

`JobWorker.consume` 做三件事：

1. 将消息 JSON 反序列化为 `JobEvent`。
2. 按 `durationMs` 睡眠，模拟业务处理。
3. 正常返回，让 Spring AMQP 确认消息。

日志示例：

```text
worker=... started job=lesson-001 durationMs=1000
worker=... completed job=lesson-001
```

## 并发模型

每个 Pod 默认一个 listener consumer，prefetch 为 1。KEDA 增加 Pod 数量，就增加并行消费者数量；这正是本 Demo 用队列积压驱动扩容的核心。

## 当前边界

Worker 不写数据库、不维护状态机、不续租、不保存 checkpoint，也没有重试或 DLQ。消息体必须自包含；如果同一消息被重复投递，业务处理也必须在生产版自行保证幂等。

## 练习

1. 创建一个 10 秒任务，观察它在 unacked 中停留多久。
2. 在任务执行期间删除 Worker Pod，观察 RabbitMQ 是否重新出现 ready 消息。
3. 将 listener concurrency 改为 2，比较单 Pod 的吞吐变化。
