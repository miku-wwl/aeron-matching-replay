# 第 02 课：系统架构

## 一条直线

```text
POST /api/jobs
      │
      ▼
JobApi ── publish JSON JobEvent ──► RabbitMQ demo.jobs.ready
                                      │
                                      ▼
                              KEDA 读取队列长度
                                      │
                                      ▼
                              Worker Deployment 0～20
```

## 角色

### API

`JobApi` 做参数校验、生成 `eventId` 和 `createdAt`，然后使用 `RabbitTemplate` 发到默认交换机对应的队列。
它不保存 Job，也不等待 Worker 完成。

### RabbitMQ

`demo.jobs.ready` 是 durable quorum queue。它暂存尚未被 Worker 取走的事件，并暴露 ready/unacked 数量供 KEDA 使用。

### KEDA

`ScaledObject` 每 5 秒读取 RabbitMQ 队列长度，目标是每个 Worker 约 1 条 ready 消息，最大 20 个副本，空闲时缩到 0。

### Worker

Worker 从队列收到完整的 `JobEvent` JSON，睡眠 `durationMs` 模拟业务处理；处理成功后消息被确认并从队列移除。

## 为什么不在这里放 Postgres

本课只演示“事件产生—消息积压—自动扩容”。把事件先写数据库再由 Scheduler 轮询，会增加一次持久化和扫描链路，且不能自动解决 DB 与 MQ 的一致性问题。
如果业务需要定时任务、查询最终状态或可靠投递，再单独加入数据库和 Outbox。

## 练习

1. 哪个指标直接触发 KEDA 扩容？
2. API Pod 重启时，已经进入 RabbitMQ 的消息是否仍然存在？
3. 当前版本如果 API 在发布前崩溃，为什么没有数据库可以恢复这次请求？
