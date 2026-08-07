# Architecture

## 部署模型

本仓库构建一个 Java 21 Spring Boot 进程：`aeron-replay-service`。Matching、Event
Publication、Media Driver 和 Aeron Archive 都属于上游基础设施。生产代码连接已有的
Archive；只有测试会启动嵌入式 `ArchivingMediaDriver`。

```text
Replay request / manifest
 recordingId, optional stopPosition, expected sequence + digest
                              |
                              v
                    ReplayController (202)
                              |
                              v
             ReplayJobManager + live progress + MDC
                              |
                              v
                   AeronReplayCoordinator
           /                  |                  \
progress checkpoint   generated SBE decode   completion proof
           \                  |                  /
                              v
               real Aeron Archive replay API
                              |
                              v
                   upstream recording runtime
```

同一个 `checkpointKey` 同时只允许一个活跃 Job。不同的 `checkpointKey` 可以并发执行，
并发数受配置的 Worker 数量限制。

## Replay 流程

1. 通过 Aeron Archive 获取 Recording 的起始 Position 和当前可用的结束 Position。
2. 校验或捕获一次有界 Replay 的 `stopPosition`。
3. 加载 Progress Checkpoint，并校验其中的 `recordingId` 与 Position。
4. 调用 `AeronArchive.startReplay(recordingId, startPosition, length, ...)`。
5. Poll Replay `Subscription`，使用 Maven 生成的 SBE Codec 解码每个 Fragment。
6. 将业务 Sequence 判定为 Duplicate、Next Event 或 Gap。
7. 只有 Next Event 才更新确定性的 Digest。
8. 完整处理成功后，才把消费进度推进到 `Header.position()`。
9. 发布不可变的实时 Progress，并定期原子写入 Progress Checkpoint。
10. 到达 Replay 边界后，写入最终 Checkpoint，并校验期望的 Sequence 与 Digest。
11. 只有校验匹配时，才原子创建独立且不可变的
    `{checkpointKey}/{attemptId}.properties` Proof，然后将 Job 标记为 `VERIFIED`。

如果 Verification 失败，已完成的 Progress Checkpoint 仍会保留，因为 Projection Effect
可能已经提交。此时不会创建 Completion Proof。之后成功的 Attempt 都会获得新的
`attemptId`，因此普通重试和 no-op Verification 都不会覆盖历史证据。

## Position 与 Sequence 不变量

| Value | Domain | Purpose |
|---|---|---|
| `eventSequence` | business event order | idempotency and gap detection |
| `lastAppliedAeronPosition` | transport byte stream | Archive restart Position |
| `stopPosition` | transport byte stream | immutable replay boundary |
| `replayDigest` | canonical event stream | deterministic verification |

这些值绝不能互相替代。`Header.position()` 是 Aeron Message 完整组装并处理后的结束
Position。因此，Decode、Schema 或 Sequence Failure 都不能把 Checkpoint 推进到无效 Fragment 之后。

Sequence 规则：

```text
eventSequence <= last sequence      duplicate: no digest/application change
eventSequence == last sequence + 1  apply: update digest and applied count
eventSequence >  last sequence + 1  fail immediately with SEQUENCE_GAP
```

Duplicate 仍然会推进已消费的 Aeron Position，也会计入已处理消息的 Checkpoint Cadence。

## 持久化产物

### Progress Checkpoint

存放于 `runtime/checkpoints`，只用于重启恢复：

```properties
checkpointKey=orders-projection
recordingId=42
lastAppliedEventSequence=12425
lastAppliedAeronPosition=1349472
appliedEventsTotal=12425
duplicatesTotal=2
replayDigest=18013645834701933210
updatedAt=2026-08-02T00:00:00Z
```

### Completion Proof

只在 Verification 成功后写入：

```text
runtime/checkpoints/completion-proofs/
  orders-projection/
    5c40f71e-6417-4ac3-a775-0a18542027db.properties
```

```properties
jobId=e97a6293-9a21-4954-a657-f407ca271b40
attemptId=5c40f71e-6417-4ac3-a775-0a18542027db
correlationId=recovery-2026-08-02
checkpointKey=orders-projection
recordingId=42
replayStartPosition=434080
replayStopPosition=1349472
finalEventSequence=12425
finalReplayDigest=18013645834701933210
resumedFromCheckpoint=true
verificationStatus=VERIFIED
completedAt=2026-08-02T00:00:01Z
```

Checkpoint 替换使用 `force(true)` 的临时文件，并要求在同一文件系统内 Atomic Move。
Proof 创建先强制写完临时 inode，再通过 Atomic Hard Link 创建最终文件；如果该 Attempt
的目标已经存在，Link 创建会失败。这样可以保留不可变的 Attempt 历史。上述是支持相应
语义的文件系统上的 Process Crash 不变量，不代表父目录、设备或远程副本已经提交。

## Digest 定义

可恢复的 rolling FNV-64 Digest 严格使用以下 canonical 顺序：

```text
eventSequence
eventType
orderId
contraOrderId
tradeId
symbolId
side
price
quantity
remainingQuantity
```

Timestamp、SBE Schema Version、可选的 v2 `sourceId` 和 Aeron Transport Metadata 都不参与
计算。该 Digest 证明的是事件流处理的确定性，不是完整的 OrderBook 或数据库 State Hash。

## Schema Evolution

SBE Codec 在 Maven `generate-sources` 阶段根据 Schema Version 2 生成。当前 Decoder 支持：

- v1 Message：没有 `sourceId`，默认值为 0；
- v2 Message：可以包含可选的 `sourceId`；
- 不支持未来的 Acting Version，遇到时返回 `UNSUPPORTED_SCHEMA`。

封装生成的 Decoder 前，会针对具体 Template，将 `actingBlockLength` 与生成代码中的
v1/v2 最小长度进行校验。Block 过短或损坏时返回 `SBE_DECODE_FAILED`，并带上两个长度
以及 Fragment 结束 Position；Checkpoint 保持在前一个有效 Fragment。未知 Template
返回 `UNSUPPORTED_TEMPLATE`，与 Schema 层 Failure 分开处理。

该字段被有意排除在 Digest 之外，因此同一个历史业务事件使用任一支持的 Encoding 读取时，
不会改变它的 Proof。

## Progress 与 Timeout 模型

Handler 会发出不可变的 `ReplayProgress` Snapshot。Position 单调递增且不超过 stop Position，
并驱动 no-progress Watchdog。任何 Position 前进都会刷新 Watchdog，因此健康的大 Replay
可能运行超过 `noProgressTimeout`。可选的 `maximumReplayDuration` 是另一个独立的绝对时长
限制。Coordinator 使用可注入的 monotonic clock，使整个 Job 生命周期中的 Timeout 行为能够
被确定性测试。

## Archive Durability 边界

不要把以下几个事实混为一谈：

1. `publication.offer(...) > 0`: Aeron accepted the message and assigned a
   stream Position.
2. `recordingPosition >= publishedPosition`: Archive reports the bytes as
   recorded and available for replay.
3. Device durable: storage has met the configured power-loss contract.
4. Replicated/cluster committed: a separate durability policy has acknowledged
   the data.

Integration Fixture 只等待第 2 步，是为了消除测试竞态。这种延迟与可靠性之间的取舍适合
本项目演示，但不代表 OKX 生产实现。生产设计可以采用异步 Recording、Replication、
Aeron Cluster 或其他 Journal。

## External Projection 边界

`ProjectionState` 是内存中的 Reference Consumer。如果将它替换成 Database 或 Remote Side
Effect，那么 Effect、Deduplication/Inbox Record 和 Checkpoint 必须处于同一个 Transactional
Recovery Unit 中。单独的 Atomic Checkpoint File 无法让互不相关的外部写入自动具备幂等性。
