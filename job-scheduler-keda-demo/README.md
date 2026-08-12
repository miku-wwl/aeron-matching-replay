# Minimal RabbitMQ + KEDA Demo

这是一个用于学习 KEDA 的最小 Spring Boot Demo，与父目录中的 Aeron 项目完全隔离。

## 核心链路

```text
HTTP API
  -> RabbitMQ durable queue: demo.jobs.ready
  -> KEDA 根据 ready queue 长度扩容 Worker（0～20）
  -> Worker 消费 JSON JobEvent，模拟执行
```

本版本是有意的“纯删减”教学实现：没有 Postgres、Flyway、数据库轮询 Scheduler，
也没有 Transactional Outbox、Lease、Heartbeat、Fencing Token、Checkpoint、Retry/DLQ
或自定义业务 Metrics。API 接受请求后直接发布消息；因此它适合学习消息队列和 KEDA，
不承诺数据库与消息之间的生产级可靠投递。

主代码只有 5 个 Java 文件：

```text
JobDemoApplication.java  Spring Boot 入口
JobApi.java              HTTP API，直接发布 JobEvent
JobEvent.java            RabbitMQ 消息载荷
JobWorker.java           消费消息并模拟执行
RabbitConfig.java        声明 durable quorum queue
```

## 本地组件

| 组件 | 地址 |
|---|---|
| API | <http://localhost:18080> |
| RabbitMQ AMQP | `localhost:15673` |
| RabbitMQ UI | <http://localhost:15674> |
| k3d cluster / namespace | `job-demo` / `job-demo` |

RabbitMQ 用户名和密码在 Git 忽略的 `.env.local` 中；密码由脚本生成，不写入 YAML。

完整学习课程见 [`docs/README.md`](docs/README.md)，每节课包含源码入口、动手命令和练习题。

## 启动

```powershell
cd D:\workshop\aug\aeron-matching-replay\job-scheduler-keda-demo
Set-ExecutionPolicy -Scope Process Bypass
./scripts/prerequisites.ps1
./scripts/up-infra.ps1
./scripts/create-cluster.ps1
./scripts/deploy.ps1
./scripts/smoke-test.ps1
```

API 固定 2 个副本；Worker 由 KEDA 管理，空闲时为 0，最大为 20。

## 创建任务

```powershell
$body = @{ jobKey = 'learn-001'; durationMs = 1000 } | ConvertTo-Json
Invoke-RestMethod http://localhost:18080/api/jobs `
  -Method Post -ContentType application/json -Body $body
```

接口返回 `202 Accepted` 和事件内容。事件已经进入 RabbitMQ 后，Worker 会异步消费。

批量压测：

```powershell
./scripts/demo-burst.ps1
```

脚本会发布 500 个 1 秒任务，验证 Worker 最终扩容到 20，队列清空后再缩容到 0。

## 验证与清理

```powershell
./scripts/verify.ps1
./scripts/e2e-test.ps1
./scripts/destroy.ps1             # 保留 RabbitMQ 数据卷
./scripts/destroy.ps1 -RemoveData # 同时删除 RabbitMQ 数据卷
```
