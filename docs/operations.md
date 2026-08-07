# Operations

## 构建、验证与演示

```powershell
.\mvnw.cmd --version
.\mvnw.cmd -ntp clean verify
.\scripts\demo-replay.ps1
```

Wrapper 会从 Maven Central 下载 Maven 3.9.9。`clean verify` 会在
`target/generated-sources/sbe` 重新生成 SBE Java 代码，运行 Unit Test 和 API Test，
启动真实的嵌入式 `ArchivingMediaDriver` 执行 Integration Test，并使用
`Runtime.halt(77)` 终止子 Replay JVM，以验证 Hard Crash Recovery。Demo 会执行聚焦的
Crash Workflow，失败时返回非零退出码。

删除 `target/` 是安全的。生成的 SBE Encoder 和 Decoder 属于构建产物，会在
`generate-sources` 阶段重新生成。

## 打包与运行

```powershell
.\mvnw.cmd -ntp package
.\scripts\run-service.ps1 -AeronDirectory "D:\aeron\driver"
```

辅助脚本会提供 Aeron 所需的 JVM Module Open 参数。生产服务要求连接上游 Media Driver
和 Archive，绝不会在进程内嵌入它们。

## 配置

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `AERON_DIR` | JVM temp `aeron-default` | Media Driver directory |
| `REPLAY_CHECKPOINT_DIR` | `./runtime/checkpoints` | Progress checkpoints and completion proofs |
| `REPLAY_CHANNEL` | `aeron:ipc` | Archive replay channel |
| `REPLAY_STREAM_ID` | `1002` | Replay stream |
| `REPLAY_NO_PROGRESS_TIMEOUT` | `20s` | Maximum time without Position advance |
| `ARCHIVE_REQUEST_TIMEOUT` | `20s` | Archive connect/control request timeout |
| `REPLAY_FRAGMENT_LIMIT` | `20` | Fragments per subscription poll |
| `REPLAY_CHECKPOINT_EVERY_PROCESSED_MESSAGES` | `100` | Handled messages between checkpoint writes |
| `REPLAY_WORKER_COUNT` | `1` | Concurrent jobs with distinct checkpoint keys |
| `REPLAY_QUEUE_CAPACITY` | `100` | Pending task capacity |
| `ARCHIVE_CONTROL_REQUEST_CHANNEL` | `aeron:ipc?term-length=64k` | Archive request channel |
| `ARCHIVE_CONTROL_REQUEST_STREAM_ID` | `10` | Archive request stream |
| `ARCHIVE_CONTROL_RESPONSE_CHANNEL` | `aeron:ipc` | Archive response channel |
| `SERVER_PORT` | `8080` | HTTP port |

`maximumReplayDuration` 默认不设置。只有在策略明确要求时，才配置独立的绝对时长限制，例如：

```powershell
$env:MATCHING_REPLAY_MAXIMUM_REPLAY_DURATION = "30m"
.\scripts\run-service.ps1 -AeronDirectory "D:\aeron\driver"
```

Checkpoint Cadence 会统计所有成功处理的 SBE Message，包括 Duplicate。Duplicate 虽然
没有业务 Effect，但其消费过的 Aeron Position 仍然必须变得可持久恢复。

IPC 默认值假设 Service 与 Archive Control Client 共享同一个 Media Driver Runtime。
如果使用 UDP Control，请按具体部署配置 Request Endpoint 和 Response Endpoint。

## Health、Job 与 Progress

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/v1/replays
Invoke-RestMethod http://localhost:8080/actuator/metrics
```

Archive Connectivity 会在 Job 开始时检查，因此上游维护期间，空闲服务仍可保持 Health。
Job Resource 会暴露单调递增的 Current Position、Stop Position、百分比、最后 Sequence、
本次运行 Counter、Checkpoint Position、吞吐量和最后 Progress 时间。

## Structured Lifecycle Log

Lifecycle event names are:

```text
REPLAY_REQUEST_ACCEPTED
REPLAY_STARTED
REPLAY_RESUMED
REPLAY_CHECKPOINTED
REPLAY_BOUNDARY_REACHED
REPLAY_VERIFIED
REPLAY_VERIFICATION_FAILED
REPLAY_FAILED
```

每个 Lifecycle Event 都包含 `jobId`；MDC 携带 `jobId`、`attemptId`、`correlationId` 和
`recordingId`。日志会按需包含 Position、Sequence、Counter、Failure Code 和 Duration。
日志只记录 Lifecycle/Checkpoint 边界，不会为每个 Event 单独打印。

## Micrometer Metrics

Actuator 暴露以下低基数 Meter：

| Meter | 含义 |
|---|---|
| `replay.jobs` with terminal `status` tag | 按终态统计的已完成 Job |
| `replay.duration` | 执行耗时 |
| `replay.events.applied` | 新应用的 Event |
| `replay.duplicates` | 被抑制的 Duplicate |
| `replay.sequence.gaps` | 检测到的 Sequence Gap |
| `replay.checkpoint.writes` | 成功写入的 Progress Checkpoint |
| `replay.checkpoint.write.failures` | Checkpoint 写入失败 |
| `replay.position.lag` | 活跃 Job 尚未处理的 Position 总量 |
| `replay.no.progress.timeouts` | 被 No-progress Watchdog 停止的 Job |

任何 Meter 都不会把 `jobId`、`correlationId`、`recordingId` 或 `checkpointKey` 用作 Label。

示例：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/metrics/replay.jobs
Invoke-RestMethod http://localhost:8080/actuator/metrics/replay.position.lag
```

## Replay 状态处理

Progress Checkpoint 直接存放在配置目录中。Completion Proof 是独立的不可变文件，路径为
`completion-proofs/{checkpointKey}/{attemptId}.properties`。不要手动编辑这两类产物。

Checkpoint 写入会强制写入临时文件，并要求在同一文件系统内 Atomic Replace。Proof 写入
会先强制写完临时内容，再原子创建 Hard Link；如果 Attempt 已存在，创建会失败。不支持
Atomic File System 行为时，Job 会失败，不会静默降级。这不保证父目录 `fsync`、设备持久化
或 Replication；应根据部署的 Failure Model 选择存储方案。

如果要主动删除本地开发环境的 Replay 状态：

```powershell
.\scripts\clean-data.ps1 -Confirm
```

该命令会删除 Progress Checkpoint 和 Completion Proof，但会保留
`runtime/checkpoints/.gitkeep`。

## Failure 行为

- Decode/Schema/Template/Block-Length Failure 或 Sequence Gap 会立即停止，不能把
  Checkpoint 推进到无效 Fragment 之后。
- 只要 Position 持续前进，健康的 Replay 可以运行超过 `REPLAY_NO_PROGRESS_TIMEOUT`。
- 停滞的 Replay 会以 `NO_PROGRESS_TIMEOUT` 失败，并返回实际/配置的 Timeout 以及最后
  成功处理的 Position/Sequence。
- 到达有界 Position 时会写入最终 Progress Checkpoint。
- Verification Mismatch 会保留该 Checkpoint，返回期望值/实际值，并且不创建新的 Proof。
- 只有 `VERIFIED` Attempt 才会创建新的不可变 Completion Proof。共享同一个
  Checkpoint Key 的 Replay（包括 no-op Verification）也会各自保留 Proof 文件。

## Publication、Recording 与 Durability

Publication Accepted、Archive Recording Progress、Device Durable 和
Replicated/Cluster Committed 是不同的里程碑。Integration Fixture 等待
`recordingPosition >= publishedPosition`，只是为了消除测试竞态。这是 MVP 的验证取舍，
不代表 OKX 生产实现。生产系统可以选择异步 Archive Recording、Replication、Aeron
Cluster Log 语义或其他 Durability Policy。
