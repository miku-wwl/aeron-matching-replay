# Aeron Replay 六项 Review 报告

Review 日期：2026-08-02
Review 范围：当前单体 Spring Boot `aeron-replay-service`、Maven 构建、真实
Aeron Archive 集成测试、Checkpoint/Completion Proof、API 与运维文档。

## 总结

| # | Review 项 | 结果 |
|---:|---|---|
| 1 | SBE 由 Maven `generate-sources` 生成 | PASS |
| 2 | Replay 调用真实 Aeron Archive API | PASS |
| 3 | Checkpoint 保存完全处理后的 `Header.position()` | PASS |
| 4 | `eventSequence` 与 Aeron Position 彻底分离 | PASS |
| 5 | Consumer 对重复事件幂等 | PASS |
| 6 | 崩溃恢复 Replay Digest 等于 uninterrupted run | PASS |

最终验证命令：

```powershell
.\mvnw.cmd -ntp clean verify
.\scripts\demo-replay.ps1
```

测试覆盖真实 `ArchivingMediaDriver`、Archive Recording/Replay、bounded
replay、duplicate、gap、无效/未来 SBE、verification mismatch、无进展超时，
以及子 JVM `Runtime.halt(77)` 后恢复。

## 1. SBE 确实由 Maven generate-sources 生成

结果：PASS。

证据：

- [`pom.xml`](../pom.xml) 的 `generate-sbe-codecs` execution 绑定
  `generate-sources`，调用官方 `uk.co.real_logic.sbe.SbeTool`。
- Schema 单一来源为
  [`matching-events.xml`](../src/main/resources/sbe/matching-events.xml)，输出仅在
  `target/generated-sources/sbe`；仓库不提交生成的 codec。
- `build-helper-maven-plugin` 把该目录加入编译源路径。
- 生产适配器 `MatchingEventSbeEncoder`、`MatchingEventSbeDispatcher` 直接引用
  `codec.generated` 类型，没有手写 offset/序列化替代品。
- `clean verify` 先删除 `target`，仍能重新生成、编译并通过 codec 测试。因此
  `target` 可安全删除，decode/encode 类会在下一次 Maven 构建生成。
- 当前 v2 decoder 能读取 v1（新增 `sourceId` 缺省为 0）和 v2，未来 acting
  version 返回结构化 `UNSUPPORTED_SCHEMA`。

相关测试：

```text
v2DecoderReplaysV1Recording
v2DecoderReplaysV2Recording
futureSchemaVersionFailsClearly
```

## 2. Replay 确实调用 Aeron Archive API

结果：PASS。

证据：

- [`AeronReplayCoordinator`](../src/main/java/io/github/mikuwwl/matchingreplay/aeron/AeronReplayCoordinator.java)
  调用 `getStartPosition`、`getRecordingPosition`/`getStopPosition`、
  `startReplay`、`stopReplay`。
- 数据来自绑定 replay session id 的真实 Aeron `Subscription`；生产路径没有
  Archive segment 文件读取器，也没有文件模拟 replay。
- 测试夹具实际启动 `ArchivingMediaDriver`，通过
  `AeronArchive.startRecording` 和 `ExclusivePublication` 录制 SBE，再调用相同
  Coordinator 回放。
- 生产 JAR 不启动内置 Archive；嵌入式 Archive 只在测试/一键演示中存在。

`boundedReplayStopsAtCapturedPosition` 还证明 stop Position 被一次性捕获，边界后
追加的消息不会被本次 replay 消费。

## 3. Checkpoint 保存的是完整处理后的 Header.position()

结果：PASS。

关键执行顺序：

```text
完整 Aeron fragment
  → SBE decode
  → sequence 验证
  → apply 或幂等判重
  → ProjectionState 接收 Header.position()
  → 发布进度
  → 按 processed-message cadence 原子写 Checkpoint
```

这里的 `Header.position()` 是已处理消息的结束 Position，不是 fragment 开始
offset。decode、schema 或 sequence 失败会在推进 Position 前终止。

证据：

- `gapLeavesCheckpointAtLastGoodMessage`：gap 后 Checkpoint 仍是上一条有效事件。
- `invalidSbeDoesNotAdvanceCheckpoint`：无效 SBE 不会被当成已消费。
- 硬崩溃测试精确断言 sequence 400 的 Checkpoint Position 等于第 400 次成功
  `publication.offer` 返回的消息结束 Position；恢复首条为 401。
- Checkpoint 临时文件先 `force(true)`，再要求同文件系统 `ATOMIC_MOVE`。不支持时
  返回 `CHECKPOINT_WRITE_FAILED`，不会退化成非原子覆盖。

该结论证明支持原子移动文件系统上的进程崩溃恢复，不等于父目录已 `fsync`、设备
完成断电安全刷盘或状态已复制。

## 4. eventSequence 与 Aeron Position 完全分离

结果：PASS。

| 字段 | 含义 | 作用 |
|---|---|---|
| `lastAppliedEventSequence` | 业务顺序 | duplicate/gap 与最终业务校验 |
| `lastAppliedAeronPosition` | Archive 字节流进度 | 恢复时 `startReplay` 起点 |
| `replayStopPosition` | 有界回放终点 | 控制本次 replay 范围 |

`Checkpoint` 分别持久化 sequence 和 Position；API 分别表达
`expectedLastEventSequence` 与 `stopPosition`。测试还断言 sequence 400 的
Position 数值不等于 400，防止两个概念被意外混用。

## 5. 重复事件处理幂等

结果：PASS（针对当前 reference projection）。

`eventSequence <= lastAppliedEventSequence` 时：

- 不重复执行 apply；
- 不更新 `replayDigest`；
- 不增加 `appliedEventsThisRun/Total`；
- 增加 `duplicatesThisRun/Total`；
- 在消息已被成功判重后推进 Aeron Position。

`duplicateDoesNotChangeReplayDigest` 在真实 Archive replay 中验证上述行为，并验证
duplicate 也计入 `checkpointEveryProcessedMessages`，因为它的 Position 仍须持久化。

若将内存 Projection 换成数据库或远程调用，业务效果、Inbox/dedup 记录和
Checkpoint 必须形成同一事务恢复单元；本地原子文件不能自动保证外部副作用幂等。

## 6. 崩溃恢复后的 Replay Digest 与 uninterrupted run 一致

结果：PASS。

完整证明链：

1. 在真实 Archive 中录制 1,000 个确定性 SBE 事件。
2. 完整回放 1..1000，得到 uninterrupted replay digest。
3. 独立子 JVM 回放同一 recording，在 sequence 400 的 Checkpoint 成功后立即
   `Runtime.halt(77)`；不执行 shutdown hook。
4. 父进程验证 Checkpoint 为 sequence 400、对应消息结束 Position 和前 400 条
   digest，并确认没有 Completion Proof。
5. 用全新的 Archive client、Checkpoint repository、Coordinator 模拟服务重启。
6. 从保存 Position 开始，第一条新 apply 的 sequence 必须为 401。
7. 最终 sequence=1000、`appliedEventsTotal=1000`、duplicate=0、gap=0。
8. resumed digest 同时等于 uninterrupted digest 和请求期望值；随后才生成
   `VERIFIED` Completion Proof。

一键证明：

```powershell
.\scripts\demo-replay.ps1
```

## Digest 定义与 Completion Proof

`replayDigest` 是可恢复的 rolling FNV-64 事件流摘要，不宣称为 OrderBook 或完整
数据库状态 hash。固定字段顺序为：

```text
eventSequence, eventType, orderId, contraOrderId, tradeId,
symbolId, side, price, quantity, remainingQuantity
```

timestamp、schema version、v2 `sourceId`、Aeron transport metadata 不参与摘要。

Progress Checkpoint 与 Completion Proof 已分离：

- Checkpoint 表示“截至某个 Aeron Position 已完整处理”，用于崩溃恢复；
- Completion Proof 表示“有界 replay 到达终点且 sequence/digest 均通过校验”；
- mismatch 保留合法进度，但不创建或覆盖已存在 Proof。

`verificationMismatchDoesNotCreateOrOverwriteCompletionProof` 对此做了真实 Archive
集成验证。

## Archive 耐久性工程取舍

本项目严格区分：

| 层级 | 能证明什么 |
|---|---|
| Publication accepted | Aeron 接受并分配 stream Position |
| Archive recorded/available | `recordingPosition` 达到该 Position |
| Device durable | 存储满足选定的断电模型 |
| Replicated/cluster committed | 额外复制或一致性策略完成 |

测试等待 `recordingPosition >= publishedPosition` 是为了消除竞态，适合 MVP
可靠性证明但会增加延迟。它不能表述成 OKX 生产系统做法，也不自动等于设备持久化。
生产可按延迟和丢失预算选择异步录制、Archive replication、Aeron Cluster Log 或
其他 journal；本仓库不声称 OKX 实际采用哪一种。

## OrderBook 与项目边界

当前生产项目只有 replay 服务，不包含 OrderBook、不重新撮合、不重放 command
进入 matching logic。历史文档中的最小 OrderBook 只是早期演示事件生成方案，不
代表作者在 OKX 负责 OrderBook。该文档已移到
[`docs/legacy`](legacy/original-mvp-guide.md)，不再属于主要阅读路径。

仍需生产接入方决定：外部 Projection 的事务边界、Archive/Catalog/Checkpoint 的
存储保证、复制/Cluster 策略，以及 HTTP job 历史的持久化需求。
