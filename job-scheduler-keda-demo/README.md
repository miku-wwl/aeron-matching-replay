# Minimal Job Scheduler + RabbitMQ + KEDA Demo

这是一个用于学习 KEDA 的最小 Spring Boot Demo，与父目录中的 Aeron 项目完全隔离。

## 只保留一条核心链路

```text
HTTP 创建 Job
  -> Postgres 保存为 SCHEDULED
  -> Scheduler 抢占到期 Job，改为 QUEUED
  -> Scheduler 将 jobId 发送到 RabbitMQ
  -> KEDA 根据 ready queue 长度扩容 Worker（0～20）
  -> Worker 取出 Job，模拟执行，更新为 SUCCEEDED
```

主代码只有 5 个 Java 文件：

```text
JobDemoApplication.java  Spring Boot 入口和定时任务开关
JobApi.java              创建、查询和批量创建 Job
JobScheduler.java        Postgres -> RabbitMQ
JobWorker.java           RabbitMQ -> 执行 -> Postgres
RabbitConfig.java        声明一条 RabbitMQ queue
```

数据库只有一个 Flyway SQL、一张 `demo_job` 表和五个状态：

```text
SCHEDULED -> QUEUED -> RUNNING -> SUCCEEDED
                                  FAILED
```

这是学习 Demo，已主动删除 Transactional Outbox、Lease、Heartbeat、Fencing Token、
Checkpoint、Retry/DLQ 和自定义 Metrics。两个 Scheduler 仍使用
`FOR UPDATE SKIP LOCKED`，避免同时领取同一个 Job；但没有 Outbox，不能把
Postgres 与 RabbitMQ 做成生产级可靠的原子投递。

## 本地组件

| 组件 | 地址 |
|---|---|
| API | <http://localhost:18080> |
| Postgres | `localhost:15432` |
| RabbitMQ AMQP | `localhost:15673` |
| RabbitMQ UI | <http://localhost:15674> |
| k3d cluster / namespace | `job-demo` / `job-demo` |

RabbitMQ 用户名和密码在 Git 忽略的 `.env.local` 中。

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

API 和 Scheduler 固定各 2 个副本；Worker 由 KEDA 管理，空闲时为 0，最大为 20。

## 创建 Job

```powershell
$body = @{
  jobKey = "learn-001"
  durationMs = 1000
} | ConvertTo-Json

$job = Invoke-RestMethod http://localhost:18080/api/jobs `
  -Method Post -ContentType application/json -Body $body

Invoke-RestMethod "http://localhost:18080/api/jobs/$($job.jobId)"
```

`scheduledAt` 可以省略，也可以设置为未来的 UTC 时间。

## 观察 KEDA 0 -> 20 -> 0

先观察 Worker：

```powershell
kubectl get pods -n job-demo -l app.kubernetes.io/component=worker -w
```

另一个窗口运行：

```powershell
./scripts/demo-burst.ps1
```

脚本会创建 500 个 1 秒任务，验证 20 个 Worker 全部 Ready，任务完成后再验证缩容到 0。

## 验证与清理

```powershell
./scripts/verify.ps1
./scripts/e2e-test.ps1
./scripts/destroy.ps1             # 保留 Postgres/RabbitMQ 数据
./scripts/destroy.ps1 -RemoveData # 同时删除 Demo 数据卷
```
