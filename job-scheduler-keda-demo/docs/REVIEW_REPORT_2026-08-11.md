# Job Scheduler + KEDA Demo 审查与端到端验收报告

日期：2026-08-11  
范围：`job-scheduler-keda-demo/` 目录；未修改父目录中的 Aeron replay 工程。  
统计工具：CLOC 1.98；排除 `target/` 和本报告自身。

## 1. 结论

项目已经达到可运行 Demo 的目标：单一 Spring Boot JAR 通过 runtime role 拆分 API、Scheduler、Worker；Postgres 与 RabbitMQ 运行于 Docker Compose；Kubernetes 与 KEDA 运行于独立 k3d 集群。最终 Maven 测试、容器镜像、Flyway 迁移、Kubernetes 部署、KEDA 扩缩容和端到端任务执行均已通过。

本轮审查修复了两个会影响正确性的高优先级问题：

1. Outbox 发布时，旧实现可能在 `QUEUED` 状态提交前就让 Worker 收到 MQ 消息，造成消息被提前 ACK、Job 留在 `QUEUED`。现在 Scheduler 在同一数据库事务中提交 `QUEUED + Outbox`，Publisher 只处理已提交的 Outbox。
2. 旧 Worker 只在任务单元完成后 heartbeat；单个单元耗时超过 Lease 时会丢失租约。现在等待期间每 250 ms 检查一次，并按配置周期续租。

清理后没有发现应继续删除的源文件。15 个 PowerShell 脚本中，每个都承担不同的部署、快速检查或故障演示职责；5 个 Kubernetes YAML 也都被 Kustomize 引用。已删除的可再生内容是 Maven `target/`（53 个文件，31.86 MiB），并删除了 RabbitMQ 中不再使用的空 `demo.jobs.retry` 队列。

## 2. 审查发现与处理

| 等级 | 发现 | 影响 | 处理结果 |
|---|---|---|---|
| 高 | Outbox confirm 发生在 `QUEUED` 提交前 | 已运行 Worker 可能先消费并 ACK，随后 Job 无消息可取 | Scheduler 改为原子提交 `QUEUED + Outbox`；Publisher 不再改变 Job 状态；增加并发测试断言 |
| 高 | 长任务单元内没有 heartbeat | `unitDelayMs > lease-duration` 时可能被接管，产生重复 attempt | 增加分段等待、周期 heartbeat 和租约/heartbeat 参数校验；E2E 用 35 秒单元验证 30 秒 Lease |
| 中 | `DISPATCHING` 仅用于上述不安全窗口 | 增加状态机复杂度且无业务价值 | Java 枚举移除；Flyway V3 将遗留行改为 `QUEUED` 并收紧 CHECK 约束 |
| 中 | RabbitMQ 声明了未使用的 retry queue | 与实际的 Postgres `RETRY_WAIT/next_run_at` 重试模型冲突，误导运维 | 删除配置、绑定与运行时空队列；保留 ready queue 和 DLQ |
| 中 | `demo_attempt` 缺少 `(job_id, attempt_number)` 数据库唯一性 | 并发缺陷可能生成重复 attempt number | Flyway V2 增加唯一约束 |
| 中 | 长心跳与 burst 同时执行的测试断言不稳定 | HPA 合法缩容会随机终止忙 Pod，导致合法的优雅恢复被判失败 | E2E 分阶段运行；长心跳必须一次完成；burst 允许且只允许 `GRACEFUL_SHUTDOWN` 重试 |
| 低 | Scheduler 与 Outbox publisher 默认共用单个调度线程 | Rabbit confirm 最长 5 秒时可能延迟 due-job 扫描 | Spring scheduling pool 调整为 2 |
| 低 | 缺少数据库状态型指标 | 只能看到进程内 counter，无法直接判断积压和状态分布 | 增加 `demo_jobs{state}`、`demo_jobs_running`、`demo_outbox_pending` gauge |
| 低 | Postgres 测试每个方法启动容器，Rabbit 测试关闭时有 listener 噪音 | 构建慢且关闭日志不稳定 | Postgres container 改为类级静态容器；Rabbit 测试用 `@DirtiesContext(AFTER_CLASS)` 有序关闭 |
| 低 | Docker build context 会携带不需要的项目文件 | 构建上下文增大 | `.dockerignore` 改为只放行 Dockerfile 和最终 JAR |

## 3. 正确性审查

### 3.1 Scheduler 与 Outbox

现在的时序是：

```text
Scheduler transaction:
  lock due jobs with FOR UPDATE SKIP LOCKED
  -> state = QUEUED
  -> insert outbox row
  -> COMMIT

Outbox publisher transaction:
  lock one unpublished row with SKIP LOCKED
  -> publish persistent RabbitMQ message
  -> wait for publisher confirm
  -> mark published_at
  -> COMMIT
```

MQ confirm 成功而数据库最终提交失败时仍可能重复发布，这是标准 at-least-once 窗口；Worker 通过 Job 状态、Lease、fencing token 和 checkpoint 幂等处理重复消息。与旧实现不同，Worker 不会再观察到“消息已到、`QUEUED` 尚未提交”的状态。

### 3.2 Lease、Heartbeat 与 Fencing

- Lease 获取和接管使用 Postgres `clock_timestamp()`，不依赖 Pod 本地时钟。
- 同一 resource 的 takeover 会单调增加 fencing token。
- heartbeat 必须同时匹配 owner、token 且原 Lease 尚未过期。
- Worker 在一个任务单元内部持续 heartbeat，不再受单元时长限制。
- checkpoint 更新前锁定并验证 Lease；unit 和 fencing token 都不允许倒退。
- Job 最终状态、checkpoint、attempt 完成和 Lease 释放在一个事务中提交，提交成功后才 ACK MQ。
- 正常 SIGTERM 会保存 checkpoint 并进入 `RETRY_WAIT`；强杀场景由 Lease 过期和 Rabbit redelivery 恢复。

### 3.3 KEDA 缩容语义

RabbitMQ ready queue 被快速取空后，HPA 可以在仍有未完成任务时减少副本。Kubernetes 选择终止哪个 Pod 不确定，因此 burst job 出现 `GRACEFUL_SHUTDOWN` 后从 checkpoint 恢复是合法行为，不应要求所有 burst job 永远只有一个 attempt。最终 E2E 的断言是：

- 单独运行的长心跳任务必须 attempt=1；
- 12 个 burst job 必须全部成功且 checkpoint=600；
- burst 的任何额外 attempt 都必须由 `GRACEFUL_SHUTDOWN` 引起；
- 不允许其他失败原因；
- ready queue 最终无 ready/unacked 消息，Worker 回到 0。

## 4. 删除与保留

### 已删除

- `target/`：53 个 Maven 编译产物和测试报告，共 33,410,009 bytes（31.86 MiB）。可用 `mvn verify` 完整再生成。
- RabbitMQ `demo.jobs.retry`：0 条消息的未使用队列。延迟重试只由 Postgres 驱动。
- Java/Schema 中的 `DISPATCHING` 活跃状态：由 Flyway V3 兼容迁移到 `QUEUED`。

### 有意保留

- `.gitignore`、`.dockerignore`：分别隔离构建/IDE 文件和最小化镜像上下文。
- `smoke-test.ps1`：快速验收；与耗时约 3 分钟的完整 `e2e-test.ps1` 用途不同。
- 6 个 `demo-*.ps1`：分别验证 burst、scheduler contention、crash resume、fencing、outbox recovery、retry/DLQ，不能由单一 happy-path E2E 替代。
- RabbitMQ DLQ 中现有 2 条历史演示消息：属于业务测试数据而不是无用文件，未擅自清空。
- Docker Compose volume 和 k3d 集群：保留运行现场，便于复查；没有执行 destroy。

## 5. 验证记录

### Maven / Testcontainers

最终 `mvn verify`：

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖内容：

- checksum resume；
- retry backoff；
- 20 线程 Lease 竞争；
- Lease 接管和 stale fencing 拒绝；
- checkpoint 防倒退；
- 两个 Scheduler 的 `SKIP LOCKED` 竞争、每 Job 唯一 Outbox、调度后状态为 `QUEUED`；
- 真实 Postgres 17 与 RabbitMQ Testcontainers；
- Rabbit manual ACK 和重复消息幂等。

全新 Testcontainers 数据库已成功顺序应用 Flyway V1、V2、V3。

### 配置与部署

- `docker compose config --quiet`：通过。
- 15 个 PowerShell 脚本 AST 语法解析：通过。
- `kubectl kustomize kubernetes | kubectl apply --dry-run=client -f -`：全部资源通过。
- 最终镜像：`job-scheduler-keda-demo:local`，manifest list digest `sha256:47275f15fc583199b231176c65268e7e5ff9eedfda582719f87be7a07a905dc1`。
- 运行数据库：Flyway V1–V3 全部 success，`DISPATCHING` 行数为 0。
- KEDA ScaledObject：Ready=True，范围 0–20。

### 最终 Kubernetes E2E

命令：`./scripts/e2e-test.ps1`  
runId：`716c7bceae`  
结果：PASS。

```text
future scheduled gate: PASS
35s unit / 30s lease heartbeat: PASS, attempt=1
burst jobs: 12/12 SUCCEEDED
burst checkpoints at unit 600: 12/12
attempt count for all 14 run jobs: min=1, max=1
unexpected retry attempts: 0
KEDA observed workers: 1 -> 5 -> 10 -> 5 -> 1 -> 0
pending outbox rows: 0
ready queue ready/unacked: 0/0
```

本报告首次验收结束时的部署状态为 API 1/1、Scheduler 2/2、Worker 0/0；后续高可用增强已将 API 改为 2 副本。ready queue 已排空；`demo_jobs{state}`、`demo_jobs_running`、`demo_outbox_pending` 均可从 API 的 `/actuator/prometheus` 读取。

## 6. 代码量统计

### 6.1 物理总量（文件只计算一次）

CLOC 的 `code` 不含空行和注释。`.gitignore` 与 `.dockerignore` 是 2 个有效文件、共 8 条规则，但 CLOC 不识别其语言，因此文件总数包含它们、代码行总数不包含这 8 条规则。

| 区域 | 文件数 | CLOC code |
|---|---:|---:|
| Production Java | 22 | 1,143 |
| Test Java | 4 | 251 |
| Flyway SQL | 3 | 74 |
| Spring `application.yml` | 1 | 77 |
| Kubernetes / KEDA YAML | 5 | 304 |
| PowerShell scripts | 15 | 521 |
| README | 1 | 124 |
| Maven / Docker / Compose | 3 | 148 |
| `.gitignore` / `.dockerignore` | 2 | CLOC 未计；8 条规则 |
| **合计** | **56** | **2,642** |

按语言统计：

| 语言 | 文件数 | 空行 | 注释 | code |
|---|---:|---:|---:|---:|
| Java | 26 | 203 | 0 | 1,394 |
| PowerShell | 15 | 72 | 7 | 521 |
| YAML | 7 | 7 | 0 | 423 |
| Markdown | 1 | 40 | 0 | 124 |
| Maven XML | 1 | 7 | 0 | 99 |
| SQL | 3 | 8 | 0 | 74 |
| Dockerfile | 1 | 0 | 0 | 7 |
| **合计** | **54** | **337** | **7** | **2,642** |

### 6.2 与原估计逐项比较（功能视图）

这里按“实现某项功能所涉及的文件”统计。同一文件可能参与多个关注点，例如 `WorkerCoordinator.java` 同时实现 Lease、fencing、checkpoint 和 Worker 状态事务；`application.yml` 同时属于 Rabbit、Scheduler 和监控。因此本表各行不能相加，物理总量应以 6.1 为准。“差异”是相对估计最近边界；“范围内”表示无需计算偏差。

| 部分 | 原估计 文件/行 | 实际 文件/行 | 与估计差异 |
|---|---:|---:|---|
| 基础 Domain、Job 状态机、DTO | 8–12 / 500–800 | 7 / 99 | 文件低 1；行低 401 |
| HTTP API | 5–7 / 300–500 | 4 / 244 | 文件低 1；行低 56 |
| Postgres Schema、Repository | 8–12 / 700–1,000 | 6 / 479 | 文件低 2；行低 221 |
| Lease、Heartbeat、Fencing Token | 6–9 / 500–800 | 4 / 532 | 文件低 2；行数范围内 |
| Atomic Checkpoint | 4–6 / 300–500 | 3 / 320 | 文件低 1；行数范围内 |
| Job Scheduler、Retry Policy | 7–10 / 600–900 | 7 / 642 | 均在范围内 |
| Transactional Outbox | 6–9 / 500–800 | 4 / 287 | 文件低 2；行低 213 |
| RabbitMQ 配置、Publisher、Consumer、DLQ | 8–12 / 650–1,000 | 5 / 445 | 文件低 3；行低 205 |
| Worker 与简单任务模拟器 | 6–8 / 400–650 | 6 / 513 | 均在范围内 |
| Metrics、Logs、Health | 5–8 / 350–600 | 4 / 364 | 文件低 1；行数范围内 |
| Spring 配置与 Runtime Role | 4–6 / 200–350 | 5 / 395 | 文件范围内；行高 45 |
| Unit Test | 10–15 / 800–1,300 | 2 / 35 | 文件低 8；行低 765 |
| Postgres/RabbitMQ Integration Test | 10–14 / 1,000–1,600 | 2 / 216 | 文件低 8；行低 784 |
| Kubernetes End-to-End Test | 4–7 / 400–700 | 8 / 370 | 文件高 1；行低 30 |
| Docker、Compose、Testcontainers 配置 | 5–8 / 250–450 | 7 / 364 | 均在范围内 |
| Kubernetes、KEDA YAML | 12–18 / 500–850 | 5 / 304 | 文件低 7；行低 196 |
| Demo Script、Runbook、README | 8–12 / 500–900 | 16 / 645 | 文件高 4；行数范围内 |

原估计各行直接求和是 116–173 个文件、8,450–13,700 行。当前物理量是 56 个文件、2,642 行，分别比估计下限少 60 个文件和 5,808 行；约为下限的 48.3% 和 31.3%。这个差异主要来自：

1. Demo 使用 `JdbcTemplate` 和少量集中式事务协调类，没有生成 DAO/entity/mapper 层。
2. 5 个 Kubernetes 文件通过多文档 `workloads.yaml` 和 Kustomize 集中表达资源，而不是每个资源一个文件。
3. Unit/Integration 测试明显少于原估计；现有 7 个测试覆盖关键并发不变量，但尚未达到生产项目常见的边界组合密度。
4. Demo/故障脚本按场景拆开，所以脚本文件数高于估计，但总行数仍在估计范围。

## 7. 剩余风险与建议

这些项目不阻止 Demo 验收，但若向生产化演进，应优先处理：

1. **增加自动化测试密度。** 当前最大缺口是 Unit 和 Integration Test。应补参数校验、API 幂等、Outbox confirm/commit 崩溃窗口、Rabbit 不可用、数据库连接中断、SIGTERM 超时、KEDA 高频震荡等测试。
2. **Outbox 吞吐。** Publisher 为保证清晰语义，在数据库事务持锁期间最多等待 5 秒 Rabbit confirm。Demo 合理；高吞吐环境需要批量、分区、独立 relay 或 CDC，同时保留 at-least-once 幂等。
3. **缩容中的长任务。** 当前可从 checkpoint 安全恢复，但 KEDA/HPA 仍可能终止忙 Worker。生产可增加 HPA scale-down stabilization、任务级 drain/延长 termination grace period，并按任务最长单元时间设置 Lease。
4. **基础设施高可用。** Compose 中 Postgres/RabbitMQ 都是单节点，Rabbit quorum queue 在单节点上不提供节点故障容错；这只适合本地 Demo。
5. **Secret 管理。** 后续增强已移除固定密码，改为 Git 忽略的本地随机 `.env.local` 和运行时 Kubernetes Secret；本地管理员仍可读取 Docker/Kubernetes 运行时配置。
6. **业务副作用。** 示例工作只更新数据库 checkpoint。真实外部副作用必须有自己的幂等键或去重表，不能仅依赖 MQ ACK。
7. **构建告警。** Java 21 下测试成功，但 Maven 输出了 Mockito 动态 agent 的未来兼容性告警和 compilerVersion 弃用告警；升级 JDK/测试栈时应处理。

## 8. 复验命令

在项目目录执行：

```powershell
mvn verify
docker build -t job-scheduler-keda-demo:local .
./scripts/deploy.ps1 -SkipBuild
./scripts/e2e-test.ps1
```

当前现场被有意保留。检查命令：

```powershell
kubectl get pods,deploy,hpa,scaledobject -n job-demo
docker exec job-demo-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
Invoke-WebRequest http://localhost:18080/actuator/prometheus
```
