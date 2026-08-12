# RabbitMQ + KEDA 课程

这套课程围绕一条足够小、可以完整跑通的链路：

```text
HTTP API → RabbitMQ → KEDA → Worker
```

本版本刻意删除 Postgres、Flyway 和数据库轮询 Scheduler。这样可以先把消息投递、队列积压、
KEDA 扩缩容和 Worker 消费学清楚，再讨论什么时候需要数据库和 Outbox。

## 课程目录

| 课次 | 文件 | 重点 |
|---|---|---|
| 01 | [environment](01-environment.md) | Docker、k3d、kubectl、Helm 和网络 |
| 02 | [architecture](02-architecture.md) | API、RabbitMQ、KEDA、Worker 的职责 |
| 03 | [direct-publish](03-direct-publish.md) | 为什么本 Demo 不保存 Postgres，事件直接发布 |
| 04 | [http-api](04-http-api.md) | 单任务、批量任务和 202 响应 |
| 05 | [message-flow](05-message-flow.md) | JobEvent、默认交换机和消息生命周期 |
| 06 | [rabbitmq](06-rabbitmq.md) | durable queue、ready/unacked 和消费确认 |
| 07 | [keda](07-keda-autoscaling.md) | RabbitMQ 指标驱动的 0→N→0 |
| 08 | [worker](08-worker.md) | Worker 如何解析和执行事件 |
| 09 | [single-job-lab](09-single-job-lab.md) | 手工发布一个任务并观察全链路 |
| 10 | [burst-lab](10-keda-burst-lab.md) | 500 个任务冲击到 20 个 Worker |
| 11 | [testing](11-testing-and-troubleshooting.md) | Maven、Testcontainers、Smoke 和 E2E |
| 12 | [production-gap](12-production-gap.md) | 当前删减版与生产系统的边界 |

## 开始前

```powershell
cd D:\workshop\aug\aeron-matching-replay\job-scheduler-keda-demo
./scripts/prerequisites.ps1
./scripts/up-infra.ps1
./scripts/create-cluster.ps1
./scripts/deploy.ps1
```

默认 namespace 是 `job-demo`。RabbitMQ 在 Docker Compose 中运行，应用和 KEDA 在本地 k3d 中运行。

## 学习目标

- 能解释为什么 API 直接发送 RabbitMQ 消息可以降低一次数据库 IO。
- 能用 `rabbitmq-diagnostics` 或管理 UI 查看 ready、unacked 和消费者。
- 能读懂 KEDA `ScaledObject`，并观察 Worker 从 0 扩到 20 再回到 0。
- 能说明当前版本为什么没有可靠的 DB+MQ 原子投递，以及以后应在哪里加入 Outbox。
