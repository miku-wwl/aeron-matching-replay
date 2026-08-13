# 第 03 课：直接发布，而不是数据库状态机

## 目标

理解本 Demo 为什么把“保存事件、轮询数据库、再发 MQ”删掉，改成 API 直接发布完整消息。

## 两种链路

```text
数据库调度版：API → Postgres(SCHEDULED) → Scheduler → RabbitMQ → Worker
当前删减版：  API → RabbitMQ(JobEvent) → KEDA → Worker
```

当前 `JobEvent` 包含：

| 字段 | 作用 |
|---|---|
| `eventId` | 每条消息的唯一标识 |
| `jobKey` | 业务任务名 |
| `durationMs` | 模拟执行时长 |
| `createdAt` | 事件创建时间 |

## 取舍

直接发布少一次数据库写入和一次 Scheduler 扫描，延迟低、代码少，特别适合本 Demo 的高吞吐 KEDA 实验。
代价是没有最终状态查询，也没有“数据库已提交但消息未发出”的恢复点；当前版本还没有 Outbox。

## 练习

1. 如果要支持“10 分钟后再执行”，这条直接发布链路还缺什么组件？
2. 如果要查询任务最终状态，状态应该由谁保存？
3. 为什么把完整任务字段放进消息后，Worker 不需要回查数据库？
