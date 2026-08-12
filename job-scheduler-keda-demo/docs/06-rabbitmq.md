# 第 06 课：RabbitMQ 基础

## 队列

`RabbitConfig` 声明一条 durable quorum queue：

```text
demo.jobs.ready
```

API 把完整的 `JobEvent` JSON 放进消息；Worker 不需要根据 ID 回查数据库。

## 观察积压

```powershell
docker exec job-demo-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

- `messages_ready`：还没有投递给 Worker 的消息。
- `messages_unacknowledged`：已投递、正在执行的消息。
- `consumers`：当前连接到队列的 Worker 消费者数。

KEDA 的 RabbitMQ scaler 主要根据 `messages_ready` 计算期望副本数；消息正在被处理时，ready 可能已经下降，而 unacked 仍然存在。

## 管理 UI

打开 <http://localhost:15674>，登录 `.env.local` 中的账号，进入 Queues 页面查看 ready、unacked、publish 和 deliver 速率。

## 失败行为

应用配置 `default-requeue-rejected: false`。因此当前 Demo 没有重试和 DLQ：解析失败或运行时异常的消息会被拒绝并丢弃。这是为了保持课程最小，不是生产建议。

## 练习

1. 运行 500 个任务时，ready 和 unacked 如何变化？
2. 为什么 durable queue 仍然不能替代业务数据库？
3. 若要加入 DLQ，需要增加哪些 RabbitMQ 声明和消费策略？
