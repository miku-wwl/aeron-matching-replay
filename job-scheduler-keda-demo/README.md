# MQ + Scheduler + KEDA + Postgres 原子更新 Demo

这是一个与仓库现有 Aeron replay 服务完全隔离的 Infrastructure Lab：独立 Maven 工程、独立 Docker Compose project、独立 k3d cluster/namespace、独立端口和数据卷。它不包含 Aeron、SBE、Matching Engine 或 Archive，也没有改动父工程的 `pom.xml` 与 `src/`。

## 实现内容

完整路径如下：

```text
HTTP 创建 Job
  -> Postgres SCHEDULED
  -> 两个 Scheduler 用 FOR UPDATE SKIP LOCKED 竞争到期任务
  -> Job + Outbox 同一个数据库事务
  -> RabbitMQ publisher confirm 后标记 Outbox 已发布
  -> KEDA 按 ready queue length 将 Worker 从 0 扩到 20
  -> RabbitMQ competing consumers（每 Pod concurrency=1 / prefetch=1 / manual ACK）
  -> Postgres Lease + 单调递增 Fencing Token
  -> 原子 Checkpoint + 状态转换
  -> 成功 ACK，数据库调度指数 backoff 重试，最终失败 Reject 到 DLQ
```

同一个 Spring Boot JAR/Image 由 `APP_ROLE=api|scheduler|worker` 切换角色。Kubernetes 固定运行 2 个 API、2 个 Scheduler，Worker 由 KEDA 管理为 0–20 个副本。API/Scheduler 使用零不可用滚动更新、PDB 和跨节点强制分布；Worker 使用不阻塞扩容的跨节点软分布。

## 隔离边界

| 资源 | 本 Demo 使用值 |
|---|---|
| 目录 | `job-scheduler-keda-demo/` |
| Compose project | `job-scheduler-keda-demo` |
| Postgres | `localhost:15432`，named volume `job-demo-postgres-data` |
| RabbitMQ | AMQP `localhost:15673`，UI `localhost:15674` |
| k3d cluster | `job-demo`，API server `localhost:16550` |
| Kubernetes namespace | `job-demo` |
| Demo HTTP API | `http://localhost:18080` |

Postgres 和 RabbitMQ 是 Docker Compose 容器；k3d 中的 Pod 通过 `host.k3d.internal` 访问它们。RabbitMQ 使用 durable quorum queues。

## 一键式启动顺序（PowerShell）

在本目录执行：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
./scripts/prerequisites.ps1
./scripts/up-infra.ps1
./scripts/create-cluster.ps1
./scripts/deploy.ps1
./scripts/smoke-test.ps1
```

`prerequisites.ps1` 只在缺失时通过 WinGet 安装 k3d/Helm。`create-cluster.ps1` 使用 KEDA 官方 Helm chart 安装或升级 KEDA。`deploy.ps1` 会运行测试、构建单一镜像、导入 k3d 并部署。

`up-infra.ps1` 首次运行时会生成 Git 已忽略的 `.env.local`，Postgres、RabbitMQ、应用 Pod 和 KEDA 共用其中的本地随机凭据。Kubernetes Secret 由 `deploy.ps1` 在运行时创建，密码不保存在 Compose、Kubernetes YAML 或 Git 中；`.env.example` 只列出所需变量名。

常用观察命令：

```powershell
kubectl get pods,deploy,hpa,scaledobject -n job-demo -w
kubectl logs -n job-demo -l app.kubernetes.io/component=worker -f --prefix
kubectl logs -n job-demo -l app.kubernetes.io/component=scheduler -f --prefix
```

RabbitMQ 管理界面是 <http://localhost:15674>，用户名和密码读取本地 `.env.local`。
RabbitMQ 只保留实际使用的 ready queue 与 DLQ；延迟重试由 Postgres
`RETRY_WAIT/next_run_at` 驱动，不使用第二套 MQ retry queue。

## HTTP API

创建立即任务：

```powershell
$body = @{
  jobKey = "demo-job-001"
  totalUnits = 1000
  unitDelayMs = 20
  checkpointEvery = 50
  maxAttempts = 3
} | ConvertTo-Json
Invoke-RestMethod http://localhost:18080/api/jobs -Method Post -ContentType application/json -Body $body
```

`scheduledAt` 省略时立即执行；未来时间使用 UTC ISO-8601，例如 `2026-08-12T01:00:00Z`。查询接口：

```text
GET /api/jobs/{jobId}
GET /api/jobs?state=RUNNING&limit=100
GET /api/jobs/stats
POST /api/jobs/burst?count=50&totalUnits=200&unitDelayMs=20&checkpointEvery=25
```

`failUntilAttempt` 是故障注入参数：值为 `2` 时前两次执行失败，第三次成功；设成大于 `maxAttempts` 的数可以演示最终失败和 DLQ。

## 六个可重复故障实验

基础部署完成后分别运行：

```powershell
./scripts/demo-burst.ps1                  # Queue burst，KEDA 0 -> N -> 0
./scripts/demo-scheduler-contention.ps1   # 两个 Scheduler，无重复 Outbox
./scripts/demo-crash-resume.ps1           # 强杀 Worker，redelivery + checkpoint resume
./scripts/demo-fencing.ps1                # stale token 更新得到 UPDATE 0
./scripts/demo-outbox-recovery.ps1         # MQ 停机窗口，Outbox 恢复后续发
./scripts/demo-retry-dlq.ps1               # 指数重试耗尽，消息进入 DLQ
```

其中 Crash 实验使用 `--grace-period=0 --force`，用来模拟无法完成优雅关闭的进程故障。正常 SIGTERM 路径会停止新工作、保存当前 checkpoint、进入 `RETRY_WAIT`，并在 40 秒 termination grace period 内退出。

完整 Kubernetes 验收会检查未来任务不会提前调度、单个工作单元超过 Lease
时仍持续 heartbeat、KEDA 扩容、最终 checkpoint、queue 排空与缩容到零：

```powershell
./scripts/e2e-test.ps1
```

## 数据库不变量

- Scheduler 在一个事务中把 Job 改为 `QUEUED` 并插入 Outbox；提交后 Publisher 才能发送，避免 Worker 先于状态提交消费；`SKIP LOCKED` 允许两个实例安全并行。
- Outbox 是 at-least-once；RabbitMQ confirm 与数据库 commit 间仍可能重复发布，因此 Worker 必须幂等识别消息。
- Claim Job、获取 Lease、创建 Attempt 在一个事务内完成。
- Lease 到期判断和续租都使用 Postgres `clock_timestamp()`，不信任 Pod 本地时间。
- Lease 行只过期、不删除；每次接管都将 fencing token 加一。
- 每次 checkpoint 事务先 `FOR UPDATE` 锁定 Lease，并校验 owner/token/expiry。
- Checkpoint 只允许 `incomingUnit >= storedUnit` 且 `incomingToken >= storedToken`。
- 最终 checkpoint、`SUCCEEDED`/`RETRY_WAIT`/`FAILED` 状态、Attempt 完成和 Lease 释放在一个事务内提交；数据库提交后才 ACK。

## 测试与指标

```powershell
./scripts/verify.ps1
```

测试覆盖 checksum resume、指数 backoff、20 线程 Lease 竞争、Lease 接管、stale fencing、checkpoint 防倒退、两个 Scheduler 的 outbox 唯一性，以及真实 RabbitMQ manual ACK/duplicate delivery。集成测试使用 Testcontainers，Docker 不可用时会跳过容器测试。

每个 Pod 暴露 `/actuator/prometheus`，包括：

```text
demo_scheduler_dispatched_total
demo_outbox_published_total
demo_lease_acquired_total
demo_lease_conflicts_total
demo_lease_lost_total
demo_checkpoint_updates_total
demo_queue_duplicates_total
demo_queue_redelivered_total
demo_worker_active
demo_job_duration_seconds
demo_jobs{state}
demo_jobs_running
demo_outbox_pending
```

## 清理

保留 Postgres/RabbitMQ 数据卷：

```powershell
./scripts/destroy.ps1
```

同时删除两个明确命名的 Demo 数据卷：

```powershell
./scripts/destroy.ps1 -RemoveData
```

KEDA 安装命令与 RabbitMQ scaler 字段遵循 [KEDA 官方部署文档](https://keda.sh/docs/2.20/deploy/)和 [RabbitMQ Queue scaler 文档](https://keda.sh/docs/2.20/scalers/rabbitmq-queue/)；k3d 安装与 Docker/kubectl 前置条件参见 [k3d 官方文档](https://k3d.io/)。
