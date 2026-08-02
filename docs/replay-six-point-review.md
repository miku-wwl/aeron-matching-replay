# Aeron Replay 六项 Review 报告

Review 日期：2026-08-02
Review 范围：当前单体 Spring Boot `aeron-replay-service`、构建配置、测试和运维文档。

## 结论

| # | Review 项 | 结果 |
|---:|---|---|
| 1 | SBE 由 Maven `generate-sources` 生成 | PASS |
| 2 | Replay 调用真实 Aeron Archive API | PASS |
| 3 | Checkpoint 保存完全处理后的 `Header.position()` | PASS |
| 4 | `eventSequence` 与 Aeron Position 分离 | PASS |
| 5 | 重复事件的业务应用幂等 | PASS |
| 6 | 崩溃恢复 Hash 等于 uninterrupted run | PASS |

验证命令：

```powershell
.\mvnw.cmd -ntp clean verify
```

验证结果：15 个测试全部通过，0 failure、0 error、0 skipped。测试包含真实
`ArchivingMediaDriver`、实际 Archive Recording/Replay，以及子 JVM 硬终止后的恢复。

## 1. SBE 必须由 Maven generate-sources 生成

结果：PASS。

证据：

- [`pom.xml`](../pom.xml) 中 `exec-maven-plugin` 的
  `generate-sbe-codecs` execution 绑定在 `generate-sources` 阶段，调用
  `uk.co.real_logic.sbe.SbeTool`。
- Schema 唯一来源是
  [`matching-events.xml`](../src/main/resources/sbe/matching-events.xml)，输出目录是
  `target/generated-sources/sbe`。
- `build-helper-maven-plugin` 在同一生命周期把生成目录加入编译源路径。
- 生产适配器
  [`MatchingEventSbeEncoder`](../src/main/java/io/github/mikuwwl/matchingreplay/codec/MatchingEventSbeEncoder.java)
  和
  [`MatchingEventSbeDispatcher`](../src/main/java/io/github/mikuwwl/matchingreplay/codec/MatchingEventSbeDispatcher.java)
  直接引用 `codec.generated` 下的类型；仓库没有手写或提交这些生成类。
- `clean verify` 会先删除 `target`，随后仍能生成、编译并通过
  [`MatchingEventSbeCodecTest`](../src/test/java/io/github/mikuwwl/matchingreplay/codec/MatchingEventSbeCodecTest.java)。

判定：业务代码没有手写字段 Offset，也没有以手写序列化替代 SBE Tool。

## 2. Replay 必须调用真实 Aeron Archive API

结果：PASS。

证据：

- [`AeronReplayCoordinator`](../src/main/java/io/github/mikuwwl/matchingreplay/aeron/AeronReplayCoordinator.java)
  通过 `AeronArchive.getStartPosition`、`getRecordingPosition`/`getStopPosition`、
  `startReplay` 和 `stopReplay` 完成有界回放。
- Replay 数据由带 replay session id 的真实 Aeron `Subscription` 轮询，不读取
  Archive segment 文件，也没有文件读取模拟器。
- [`EmbeddedArchiveFixture`](../src/test/java/io/github/mikuwwl/matchingreplay/support/EmbeddedArchiveFixture.java)
  只存在于测试代码，实际启动 `ArchivingMediaDriver`、调用
  `AeronArchive.startRecording` 并通过 `ExclusivePublication` 写入 SBE 消息。
- [`AeronReplayCoordinatorIntegrationTest`](../src/test/java/io/github/mikuwwl/matchingreplay/aeron/AeronReplayCoordinatorIntegrationTest.java)
  对这份真实 Recording 执行 Archive Replay。

判定：生产路径和集成测试都使用 Aeron Archive API；生产 JAR 不启动测试 Archive。

## 3. Checkpoint 必须保存完全处理后的 Header.position()

结果：PASS。

证据：

- [`ReplayFragmentHandler`](../src/main/java/io/github/mikuwwl/matchingreplay/aeron/ReplayFragmentHandler.java)
  的顺序是：SBE decode → `ProjectionState.apply(event, header.position())` → 周期性
  Checkpoint。解码或业务应用失败时，不会推进或保存该消息的位置。
- 保存的是 Aeron `Header.position()` 所表示的已消费消息结束 Position，不是
  fragment 起始 offset。
- 崩溃测试记录了每次成功 `publication.offer(...)` 返回的消息结束 Position。在
  event 400 后，父进程断言子进程保存的 `lastAppliedAeronPosition` 与该 Position
  精确相等；同时恢复后的首条业务事件严格为 401。
- [`AtomicPropertiesFile`](../src/main/java/io/github/mikuwwl/matchingreplay/checkpoint/AtomicPropertiesFile.java)
  先 `force(true)` 临时文件，再要求 `ATOMIC_MOVE` 替换。文件系统不支持原子移动时
  直接失败，不再静默使用非原子覆盖。

边界：这里证明的是受支持文件系统上的进程崩溃恢复原子性，不声称父目录已
`fsync`、设备已满足任意断电模型或 Checkpoint 已复制。

## 4. eventSequence 与 Aeron Position 必须彻底分离

结果：PASS。

证据：

- [`Checkpoint`](../src/main/java/io/github/mikuwwl/matchingreplay/checkpoint/Checkpoint.java)
  分别保存 `lastAppliedEventSequence` 和 `lastAppliedAeronPosition`。
- `eventSequence` 只用于业务连续性、gap 和 duplicate 判断；Archive
  `startReplay` 只使用 Aeron Position。
- API 也分别暴露 `expectedLastEventSequence`、`stopPosition` 和
  `replayStartPosition`/`replayStopPosition`。
- 崩溃测试明确断言 event 400 的 Aeron Position 不等于数值 400，并使用前者恢复、
  后者验证业务顺序。

判定：代码中没有把业务序列当成 Archive byte Position，也没有反向替代。

## 5. Consumer 对重复事件必须幂等

结果：PASS（当前内置 Projection 范围）。

证据：

- [`ProjectionState`](../src/main/java/io/github/mikuwwl/matchingreplay/projection/ProjectionState.java)
  对 `eventSequence <= lastAppliedEventSequence` 返回 `DUPLICATE`：不重复混入
  state hash、不增加 applied count、不改变最终业务序列，但记录 duplicate count，
  并把 Aeron Position 推进到这条已经判定完成的消息结束位置。
- [`ProjectionStateTest`](../src/test/java/io/github/mikuwwl/matchingreplay/projection/ProjectionStateTest.java)
  对同一事件应用两次，验证 Hash、业务序列和 applied count 均保持不变。

生产接入边界：若把内置 Projection 换成数据库写入或调用外部服务，业务效果、
dedup/Inbox 和 Checkpoint 必须组成同一个事务性恢复单元；仅靠本地 Checkpoint
文件不能让一个无事务的外部副作用自动幂等。

## 6. 崩溃恢复后 Projection Hash 必须等于 uninterrupted run

结果：PASS。

测试路径：

1. 在真实 Archive 中录制 1,000 个确定性 SBE 事件。
2. 用独立 checkpoint key 完整回放 1..1000，得到 uninterrupted reference hash。
3. 启动独立子 JVM，命令目标仍是完整的 1..1000；在处理中间 event 400 的周期
   Checkpoint 完成后，从 Checkpoint repository 内立即调用
   `Runtime.getRuntime().halt(77)`，不等待 Replay 完成，也不执行正常 shutdown
   hooks。
5. 父进程读取 Checkpoint，验证 sequence=400、Position=第 400 条消息结束 Position、
   Hash=前 400 条 reference hash。
6. 创建全新的 Archive client factory、Checkpoint repository 和 Coordinator，
   从保存 Position 恢复；首条恢复事件为 401，末条为 1000。
7. 断言恢复结果无 gap，最终 Hash 同时等于真实 uninterrupted replay hash 和
   全量事件 reference hash。

实现见
[`AeronReplayCoordinatorIntegrationTest`](../src/test/java/io/github/mikuwwl/matchingreplay/aeron/AeronReplayCoordinatorIntegrationTest.java)
与测试子进程
[`CrashReplayProcessMain`](../src/test/java/io/github/mikuwwl/matchingreplay/support/CrashReplayProcessMain.java)。

## Archive 耐久性取舍

本项目严格区分四层状态：

| 层级 | 含义 |
|---|---|
| Publication accepted | `offer` 成功并取得 stream Position |
| Archive recorded/available | `recordingPosition` 已达到该 Position |
| Device durable | 文件和元数据满足选定的存储/断电保证 |
| Replicated or committed | 复制、Cluster Log 或其他策略已经确认 |

测试夹具等待 `recordingPosition >= publishedPosition`，是为了消除测试竞态并证明
MVP 的可恢复性；它会增加等待延迟，不能写成 OKX 生产系统的确定做法，也不能自动
提升为设备持久化或复制提交。生产系统可能按需求选择异步 Archive、Archive
replication、Aeron Cluster Log 或其他 journal；本仓库不声称 OKX 实际使用其中
任何一种方案。

## OrderBook 职责边界

当前 Spring Boot 生产代码不包含 OrderBook，也不会在 Replay 时重新撮合。服务只
读取已经发生的 Matching Events 并恢复下游 Projection。

历史指南中的最小内存 OrderBook 仅用于生成真实、确定性的演示事件，属于复现工程
设计；它不代表作者当年负责 OrderBook，也不代表 OKX 私有实现。历史指南已增加
醒目标记，并明确以当前单服务架构和本报告为准。

## 仍需由生产接入方决定的事项

- 实际外部 Projection 的事务、Inbox/dedup 和 Checkpoint 原子边界。
- Archive segment、Catalog、Checkpoint 所在存储的断电与备份策略。
- 是否采用 Archive replication、Aeron Cluster 或其他耐久性/高可用机制。
- HTTP job 状态目前是进程内运维状态；服务重启后保留的是 Checkpoint，不是旧
  job 历史。
