# 第 05 课：消息从 API 到 Worker

## 发送方式

API 使用 RabbitTemplate 的默认交换机，将 routing key 设置为 `demo.jobs.ready`：

```java
rabbit.convertAndSend("", readyQueue, objectMapper.writeValueAsString(event));
```

RabbitMQ 默认交换机会把消息直接路由到同名队列，因此不需要额外的 exchange、binding 或 Scheduler。

## 消息生命周期

```text
published → ready → delivered/unacked → acknowledged
```

- `ready`：消息在队列中等待 Worker。
- `unacked`：Worker 已取到但还没有完成处理。
- `acknowledged`：Worker 正常返回，消息从队列移除。

## 为什么没有 Scheduler

当前事件不是“未来到期任务”，而是 API 收到后立即可执行的事件。若需求变成定时执行，可以重新引入数据库扫描、RabbitMQ delayed message 或独立的调度服务；那是另一个教学主题。

## 观察命令

```powershell
kubectl logs -n job-demo -l app.kubernetes.io/component=api --prefix --tail=50
kubectl logs -n job-demo -l app.kubernetes.io/component=worker --prefix --tail=50
docker exec job-demo-rabbitmq rabbitmq-diagnostics -q ping
```

## 练习

1. `ready` 和 `unacked` 哪一个会触发 KEDA？
2. Worker 在 `Thread.sleep` 期间重启，消息会怎样？
3. 为什么生产系统通常还要设计幂等键？
