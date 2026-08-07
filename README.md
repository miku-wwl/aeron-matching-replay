# Aeron Archive Replay Reference

[![Replay Verification](https://github.com/miku-wwl/aeron-matching-replay/actions/workflows/replay-verification.yml/badge.svg)](https://github.com/miku-wwl/aeron-matching-replay/actions/workflows/replay-verification.yml)

这是一个基于 Aeron Archive 的 Replay/Recovery Reference Implementation，处理 SBE 编码的
Matching Event Stream，并展示 Bounded Replay、基于 Position 的 Checkpoint、Sequence 校验、
Crash Recovery、确定性 Verification 和可观测的 Replay Progress。

本仓库是一个 Java 21 Spring Boot Service。正常运行时，它连接上游 Aeron Media Driver 和
Archive；测试会启动真实的嵌入式 Archive，以便完整复现工作流。

## 项目展示的核心流程

```text
Capture recordingId and bounded stop Position
                    |
                    v
           Load progress checkpoint
                    |
                    v
       Start real Aeron Archive replay
                    |
                    v
        Decode Maven-generated SBE codecs
                    |
                    v
       Validate business eventSequence
          /                       \
    duplicate                  next event
        |                          |
advance Position        apply deterministic digest
          \                       /
                    v
 publish progress and atomically checkpoint Position
                    |
                    v
        resume safely after a hard crash
                    |
                    v
 verify final sequence + digest, then write proof
```

Service 使用 Aeron Archive Replay API，不会通过直接读取 Recording File 来模拟 Replay。

## 核心不变量

- **Replay Boundary：**只消费不超过捕获的 Stop Position 的 Fragment；之后追加的数据属于下一次运行。
- **Sequence：**每个新应用的 Event 都必须满足 `eventSequence = lastAppliedEventSequence + 1`。
- **Duplicate：**已应用过的 Sequence 仍会推进消费到的 Aeron Position，但不会改变 Digest 或应用计数。
- **Checkpoint：**完整 Fragment 解码并处理成功后，才保存 `Header.position()`，绝不把它与 Fragment 起点混淆。
- **Crash Recovery：**新进程从最后一次 Atomic Persist 的 Aeron Position 恢复；业务 `eventSequence` 始终是独立值。
- **Verification：**只有期望的最终 Sequence 和 Replay Digest 都匹配时，才创建不可变且绑定 Attempt 的 Completion Proof；后续使用同一 Checkpoint Key 的 Replay 不能覆盖它。

## 一键演示

前置条件：JDK 21。Maven 由 Wrapper 提供。

```powershell
.\scripts\demo-replay.ps1
```

该命令会启动真实的嵌入式 Media Driver 和 Archive，录制 1,000 个 Maven-SBE 编码的 Event，
执行一次不中断的 Replay，在 Sequence 400 的 Checkpoint 后使用 `Runtime.halt(77)` 终止子
JVM，然后由全新的 Coordinator 恢复。输出包含 Recording/Boundary Position、Crash Checkpoint、
重启后的第一个 Sequence、两个 Digest、Counter，以及：

```text
[1/6] Started embedded Aeron Archive
[2/6] Recorded 1,000 SBE events
[3/6] Completed uninterrupted replay
[4/6] Halted replay process after checkpoint sequence 400
[5/6] Resumed from saved Aeron position
[6/6] Final replay digest matched uninterrupted replay

REPLAY WORKFLOW: PASS
```

这个证明比 Graceful Restart Test 更强，因为子进程无法执行正常的 Shutdown Hook。重启后，
第一个新应用的 Event 是 Sequence 401，累计应用数为 1,000，Duplicate 为 0，恢复后的
Digest 与不中断运行的 Digest 相等。

## API 示例

让 Service 连接已有的 Media Driver 和 Archive：

```powershell
.\scripts\run-service.ps1 -AeronDirectory "D:\aeron\driver"
```

提交一个 Bounded Replay：

```http
POST /api/v1/replays
Content-Type: application/json

{
  "recordingId": 42,
  "checkpointKey": "orders-projection",
  "stopPosition": 1349472,
  "expectedLastEventSequence": 12425,
  "expectedReplayDigest": "18013645834701933210",
  "correlationId": "recovery-2026-08-02"
}
```

请求返回 `202 Accepted`。通过 `GET /api/v1/replays/{jobId}` 查询。运行中的响应会暴露
`currentPosition`、`progressPercent`、`lastEventSequence`、本次运行 Counter、最新
Checkpoint Position、吞吐量和 `lastProgressAt`。终态为 `VERIFIED`、`VERIFICATION_FAILED`
或 `FAILED`；Failure 会提供稳定的 Code 和诊断字段，不需要解析 Message。每个 Job 还会
提供独立的 `attemptId`；这两个 ID 共同标识它的不可变 Completion Proof。

Counter names are explicit:

- `appliedEventsThisRun` and `duplicatesThisRun` describe this execution.
- `appliedEventsTotal` and `duplicatesTotal` include the loaded checkpoint.
- `sequenceGapsThisRun` belongs only to this execution attempt.

完整的请求与响应示例请参阅 [API 参考](docs/api.md)。

## 测试证明的 Failure 场景

| 场景 | 证明的不变量 |
|---|---|
| Bounded Replay | 捕获的 Stop Position 之后追加的 Event 不会被消费 |
| Live Bounded Replay | Recording 仍在增长时，边界之后的 Event 仍会被忽略 |
| Duplicate Sequence | Position 会前进，但 Digest 和应用计数不会变化 |
| Sequence Gap | 以 `SEQUENCE_GAP` 失败，Checkpoint 停在最后一个有效 Event |
| Invalid/Future SBE | 应用前校验 Schema、Template、Version 和 Acting Block Length |
| Verification Mismatch | Progress 仍然有效，但不会创建或覆盖 Completion Proof |
| Repeated/No-op Verification | 每个成功 Attempt 都会获得独立的不可变 Proof |
| Hard Process Crash | 新进程从保存的 Position 恢复，并得到与不中断运行相同的 Digest |
| No Progress | 完整 Coordinator/Job 流程返回结构化 Timeout，并保留最后一个 Checkpoint |
| Schema Evolution | 当前 Decoder 可读取 v1/v2，并拒绝不支持的未来版本 |

## Replay Digest

Rolling FNV-64 Digest 是紧凑的确定性 Event Stream Proof，不是 Database 或 OrderBook State
Hash。Canonical 字段顺序为：

```text
eventSequence, eventType, orderId, contraOrderId, tradeId,
symbolId, side, price, quantity, remainingQuantity
```

Timestamp、Schema Version、`sourceId` 以及所有 Aeron Transport Metadata 都不参与计算。
当前 Digest 会持久化到 Progress Checkpoint，因此这条 Digest Chain 可以在恢复后继续计算。

## 明确的非目标

本项目不恢复 Matching Engine 或 OrderBook，不把 Command 重新送入 Matching Logic，不重建
Maker/Taker 决策，不实现 Trading Risk Control，也不提供 PostgreSQL/Kafka/Kubernetes、
Authentication 或 UI 功能。小型 Matching Event Model 仅用于生成更真实的 Replay Input。
不得将本实现理解为任何 OKX Proprietary Production Architecture 的描述。

## 构建与测试

```powershell
git clone https://github.com/miku-wwl/aeron-matching-replay.git
Set-Location aeron-matching-replay
.\mvnw.cmd --version
```

```powershell
.\mvnw.cmd -ntp clean verify
```

Maven Wrapper 会从 Maven Central 下载 Maven 3.9.9，不要求配置区域镜像。

`generate-sources` 会使用官方 SBE Tool 处理
`src/main/resources/sbe/matching-events.xml`；生成的 Java 代码只写入
`target/generated-sources/sbe`。删除 `target/` 是安全的，因为 Maven 会重新生成 Codec。

完整测试套件使用真实的 `ArchivingMediaDriver`，覆盖重点 Failure Matrix，并运行 Hard Crash
Child-JVM Test。CI 在推送到 `main` 和提交 Pull Request 时执行相同的命令。

## 运维与可观测性

Progress Checkpoint 位于 `runtime/checkpoints`；Verified Completion Proof 是以下路径下的不可变文件：
`runtime/checkpoints/completion-proofs/{checkpointKey}/{attemptId}.properties`.
Checkpoint Cadence 会统计所有成功处理的 Message，包括 Duplicate，因为它们消费到的 Aeron
Position 也必须可恢复。

Actuator 在 `/actuator/metrics` 暴露 Replay 相关的 Micrometer Metrics。Lifecycle Log 携带
MDC `jobId`、`attemptId`、`correlationId` 和 `recordingId`，不会为每个 Event 单独记录。详见：

- [Architecture](docs/architecture.md)
- [Operations](docs/operations.md)
- [Six-point replay review](docs/replay-six-point-review.md)
