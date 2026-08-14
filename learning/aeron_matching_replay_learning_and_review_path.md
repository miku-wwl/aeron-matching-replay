# Aeron Matching Replay 学习与 Review 路径

> 适用仓库：`miku-wwl/aeron-matching-replay`  
> 项目定位：基于 Aeron Archive 和 SBE 的事件流 Replay / Recovery Reference Implementation  
> 推荐对象：具备 Java、并发编程、分布式系统或消息系统基础的工程师

---

## 1. 学习目标

完成本路径后，应能够独立回答以下问题：

1. Aeron Publication、Recording、Archive Replay 分别承担什么职责？
2. 为什么 Replay 必须同时保存 Aeron Position 和业务 `eventSequence`？
3. 为什么 `Header.position()` 必须在完整解码和应用之后才能写入 Checkpoint？
4. Duplicate、Next Event、Sequence Gap 三种事件应如何处理？
5. 为什么 Replay 必须捕获固定的 `stopPosition`？
6. Progress Checkpoint 和 Completion Proof 为什么必须分开？
7. 进程被 `Runtime.halt()` 强制终止后，为什么仍能安全恢复？
8. SBE 的 `schemaId`、`templateId`、`actingVersion`、`actingBlockLength` 分别有什么作用？
9. 为什么 Replay Timeout 应优先使用 No-progress Timeout，而不是简单的总时长 Timeout？
10. 如何通过测试证明 Replay 的边界、幂等、崩溃恢复和最终一致性？

最终目标不是背代码，而是能够从头复述：

```text
Capture Boundary
    → Load Checkpoint
    → Start Archive Replay
    → Decode SBE
    → Validate Sequence
    → Apply Digest
    → Advance Position
    → Persist Checkpoint
    → Resume After Crash
    → Verify Result
    → Write Immutable Proof
```

---

# 2. 项目边界先行

在阅读代码前，先明确项目解决什么问题。

## 2.1 项目解决的问题

该仓库展示：

- 从真实 Aeron Archive Recording 中读取历史事件；
- 使用固定边界执行 bounded replay；
- 使用 Aeron Position 保存消费进度；
- 使用业务 `eventSequence` 检测重复和缺口；
- 使用 SBE 编解码匹配事件；
- 使用原子 Checkpoint 支持进程崩溃恢复；
- 使用 Replay Digest 验证恢复结果；
- 使用 Completion Proof 保存一次成功 Replay 的不可变证据；
- 通过 API、日志、Metrics 和测试展示完整恢复流程。

## 2.2 项目明确不解决的问题

不要按以下方向理解或 Review：

- 恢复完整 OrderBook；
- 重新运行撮合算法；
- 重放订单命令；
- 重建 Maker/Taker 匹配决策；
- 处理账户余额、仓位或风控；
- 模拟真实交易所生产架构；
- 提供 PostgreSQL、Kafka、Kubernetes 或前端系统。

正确定位是：

> 以最小业务模型展示完整、可信、可验证的 Replay Workflow。

---

# 3. 项目全景图

```text
HTTP Replay Request
 recordingId
 checkpointKey
 stopPosition
 expectedSequence
 expectedReplayDigest
 correlationId
        │
        ▼
ReplayController
        │
        ▼
ReplayJobManager
  ├─ asynchronous execution
  ├─ one active job per checkpointKey
  ├─ progress snapshots
  ├─ lifecycle logging
  └─ terminal metrics
        │
        ▼
AeronReplayCoordinator
  ├─ inspect recording range
  ├─ load checkpoint
  ├─ capture bounded stop
  ├─ start Aeron Archive replay
  ├─ poll subscription
  ├─ no-progress watchdog
  └─ final verification
        │
        ▼
ReplayFragmentHandler
  ├─ SBE decode
  ├─ sequence validation
  ├─ projection/digest update
  ├─ position advance
  ├─ checkpoint cadence
  └─ live progress
        │
        ├─────────────────┐
        ▼                 ▼
CheckpointRepository   CompletionProofRepository
crash recovery state   immutable verified evidence
```

---

# 4. 知识地图

## 4.1 Aeron 基础

必须理解：

- Media Driver；
- Publication；
- Subscription；
- Stream ID；
- Channel；
- IPC Channel；
- Fragment；
- FragmentAssembler；
- Backpressure；
- Publication 返回值；
- Archive Recording；
- Recording ID；
- Start Position；
- Recording Position；
- Stop Position；
- Replay Session。

重点区分：

```text
Publication Position
    Aeron 接受消息后的流位置

Recording Position
    Archive 已经记录到的位置

Device Durability
    存储设备满足掉电持久性的位置

Replicated Commit
    集群或副本已经确认的位置
```

项目测试只需要确认：

```text
recordingPosition >= publishedPosition
```

这代表事件可供 Archive Replay，不代表磁盘或集群级强持久性。

### 推荐阅读文件

```text
src/test/java/.../support/EmbeddedArchiveFixture.java
src/main/java/.../aeron/AeronArchiveClientFactory.java
src/main/java/.../aeron/AeronReplayCoordinator.java
```

---

## 4.2 Aeron Position 与业务 Sequence

这是整个项目最重要的知识点。

| 值 | 所属层次 | 用途 |
|---|---|---|
| `eventSequence` | 业务事件流 | 幂等和 Gap 检测 |
| `lastAppliedAeronPosition` | Aeron Transport | Archive 恢复起点 |
| `stopPosition` | Aeron Transport | 本次 Replay 固定边界 |
| `replayDigest` | 确定性验证 | 比较恢复前后事件处理结果 |

不能把 `eventSequence` 当作 Replay 起点，因为：

- Aeron Archive 按字节 Position 回放；
- 一条消息的编码长度可能变化；
- SBE Schema Evolution 会改变消息大小；
- Archive API 不知道业务 Sequence；
- Position 和 Sequence 属于完全不同的命名空间。

### 必须掌握的规则

```text
eventSequence <= lastAppliedSequence
    → Duplicate
    → 不更新 Digest
    → 不增加 Applied Count
    → 仍然推进 Aeron Position

eventSequence == lastAppliedSequence + 1
    → 正常应用
    → 更新 Digest
    → 增加 Applied Count
    → 推进 Aeron Position

eventSequence > lastAppliedSequence + 1
    → Sequence Gap
    → 立即失败
    → 不越过错误 Fragment 保存 Checkpoint
```

### 推荐阅读文件

```text
src/main/java/.../projection/ProjectionState.java
src/test/java/.../projection/ProjectionStateTest.java
src/test/java/.../aeron/ReplayFailureScenarioIntegrationTest.java
```

---

## 4.3 SBE 编解码

项目使用 Maven 在 `generate-sources` 阶段生成 SBE Codec。

### 必须理解

- Schema XML 是协议单一来源；
- Generated Code 不提交到仓库；
- Maven Clean 后必须能够重新生成；
- Message Header 决定使用哪个 Template；
- Decoder 必须验证来自消息头的不可信字段；
- Schema Evolution 需要保留历史兼容性。

### 关键字段

| 字段 | 含义 |
|---|---|
| `schemaId` | 协议所属 Schema |
| `templateId` | 具体消息类型 |
| `actingVersion` | 写入该消息时使用的 Schema Version |
| `actingBlockLength` | 编码时固定区块长度 |

### 项目的兼容策略

```text
v1 message
    sourceId 不存在，读取时默认 0

v2 message
    sourceId 存在

future actingVersion
    UNSUPPORTED_SCHEMA

known schema + unknown template
    UNSUPPORTED_TEMPLATE

actingBlockLength too short
    SBE_DECODE_FAILED
```

`sourceId` 不参与 Replay Digest，因此同一业务事件以 v1 或 v2 编码时，不会改变确定性验证结果。

### 推荐阅读顺序

```text
src/main/resources/sbe/matching-events.xml
pom.xml
src/main/java/.../codec/MatchingEventSbeEncoder.java
src/main/java/.../codec/MatchingEventSbeDispatcher.java
src/test/java/.../codec/MatchingEventSbeCodecTest.java
```

### 学习实验

执行：

```powershell
.\mvnw.cmd clean generate-sources
```

观察：

```text
target/generated-sources/sbe
```

然后回答：

1. 每个 Template 的 ID 是多少？
2. v1 和 v2 的固定 Block Length 有什么区别？
3. 为什么不能手写 Offset 替代生成 Codec？
4. 为什么 `actingBlockLength` 必须按 Version 检查？

---

## 4.4 Replay Digest

当前 Digest 是可恢复的 rolling FNV-64。

Canonical Field Order：

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

明确排除：

```text
timestamp
schemaVersion
sourceId
Aeron transport metadata
```

### Digest 的准确含义

它证明：

> 同一有序业务事件流在正常 Replay 和崩溃恢复 Replay 中，被确定性地处理成相同结果。

它不证明：

- 完整 OrderBook 相同；
- 数据库每行完全相同；
- 账户状态相同；
- 所有外部副作用相同。

### 推荐阅读文件

```text
src/main/java/.../domain/ReplayDigest.java
src/main/java/.../projection/ProjectionState.java
src/test/java/.../projection/ProjectionStateTest.java
```

### 自测题

假设 Sequence 100 被重复投递：

- Aeron Position 是否推进？
- Digest 是否变化？
- `appliedEventsTotal` 是否增加？
- `duplicatesTotal` 是否增加？

正确答案：

```text
Position 推进
Digest 不变
Applied Count 不变
Duplicate Count 增加
```

---

## 4.5 Progress Checkpoint

Checkpoint 用于崩溃恢复。

典型字段：

```properties
checkpointKey=orders-projection
recordingId=42
lastAppliedEventSequence=12425
lastAppliedAeronPosition=1349472
appliedEventsTotal=12425
duplicatesTotal=2
replayDigest=18013645834701933210
updatedAt=...
```

### 核心原则

Checkpoint 必须在以下步骤之后推进：

```text
完整 Fragment 组装
    → SBE 解码成功
    → Schema 检查成功
    → Sequence 检查成功
    → 业务状态或 Digest 应用完成
    → 保存 Header.position()
```

不能在 Decode 前保存 Position，否则崩溃后可能跳过未处理消息。

### 原子写入流程

项目使用：

```text
write temporary file
    → force file content
    → atomic move on same filesystem
```

目标是避免：

- 半写文件；
- 内容截断；
- 旧新版本混合；
- 崩溃后读取损坏 Checkpoint。

### 推荐阅读文件

```text
src/main/java/.../checkpoint/AtomicPropertiesFile.java
src/main/java/.../checkpoint/Checkpoint.java
src/main/java/.../checkpoint/CheckpointRepository.java
src/test/java/.../checkpoint/CheckpointRepositoryTest.java
```

---

## 4.6 Completion Proof

Completion Proof 和 Checkpoint 不是一回事。

| Artifact | 用途 | 何时写入 |
|---|---|---|
| Progress Checkpoint | 崩溃恢复 | Replay 过程中周期写入 |
| Completion Proof | 成功验证证据 | 最终 Sequence 和 Digest 都匹配后 |

Proof 路径：

```text
runtime/checkpoints/completion-proofs/
  {checkpointKey}/
    {attemptId}.properties
```

每次成功 Attempt 都有独立文件。

### 为什么不可覆盖

假设同一 `checkpointKey` 执行两次：

```text
Attempt A
    replay 0 → 100000
    VERIFIED

Attempt B
    replay 100000 → 100000
    no-op verification
    VERIFIED
```

如果共用一个 Proof 文件，Attempt B 会覆盖更有价值的 Attempt A。

因此项目使用：

```text
checkpointKey + attemptId
```

创建不可变 Proof。

### 推荐阅读文件

```text
src/main/java/.../checkpoint/CompletionProof.java
src/main/java/.../checkpoint/CompletionProofRepository.java
src/test/java/.../checkpoint/CompletionProofRepositoryTest.java
```

---

## 4.7 Bounded Replay

Replay 必须在开始时确定稳定边界。

```text
Recording 当前已到 Position 5000
捕获 stopPosition = 5000
之后继续写入到 Position 8000
本次 Replay 仍然只处理到 5000
```

如果不捕获边界，Replay 可能不断追赶新数据，任务无法形成确定性结束条件。

### 推荐阅读文件

```text
src/main/java/.../aeron/AeronReplayCoordinator.java
src/test/java/.../aeron/ReplayFailureScenarioIntegrationTest.java
```

重点测试：

```text
boundedReplayStopsAtCapturedPosition
boundedReplayDoesNotFollowEventsAppendedAfterCapturedStopPosition
```

---

## 4.8 No-progress Timeout

总执行时间长，不代表 Replay 卡死。

正确判断方式：

```text
Position 持续推进
    → Replay 健康
    → 可以超过 noProgressTimeout

Position 长时间不推进
    → Replay Stalled
    → NO_PROGRESS_TIMEOUT
```

项目另外允许配置：

```text
maximumReplayDuration
```

它是独立的绝对时长限制。

### 为什么使用 Monotonic Clock

超时计算不能依赖墙上时钟，因为系统时间可能：

- NTP 调整；
- 手动修改；
- 时区变化；
- 时钟回拨。

项目使用可注入的：

```text
MonotonicClock
```

生产实现使用 `System.nanoTime()`，测试使用可控 Clock。

### 推荐阅读文件

```text
src/main/java/.../aeron/MonotonicClock.java
src/main/java/.../aeron/SystemMonotonicClock.java
src/main/java/.../aeron/NoProgressWatchdog.java
src/test/java/.../aeron/NoProgressWatchdogTest.java
src/test/java/.../application/ReplayCoordinatorNoProgressIntegrationTest.java
```

---

## 4.9 异步 Job 与并发控制

API 返回 `202 Accepted`，Replay 在后台执行。

状态机：

```text
QUEUED
   ↓
RUNNING
   ├─ VERIFIED
   ├─ VERIFICATION_FAILED
   └─ FAILED
```

并发约束：

```text
同一个 checkpointKey
    同一时间只允许一个 Active Job

不同 checkpointKey
    可在 worker capacity 内并行
```

原因：

- 同一个 Checkpoint 被多个 Job 并发修改会产生竞态；
- 同一个 Projection 的业务恢复不能无序并发；
- `checkpointKey` 是恢复单元身份。

### 推荐阅读文件

```text
src/main/java/.../application/ReplayJobs.java
src/main/java/.../application/ReplayJobManager.java
src/main/java/.../application/ReplayJobSnapshot.java
src/main/java/.../application/ReplayJobState.java
src/test/java/.../application/ReplayJobManagerTest.java
```

---

## 4.10 API、Failure 与 Observability

### API

```text
POST /api/v1/replays
GET  /api/v1/replays/{jobId}
GET  /api/v1/replays
```

核心输入：

```text
recordingId
checkpointKey
stopPosition
expectedLastEventSequence
expectedReplayDigest
correlationId
```

### Failure Code

重点理解：

```text
RECORDING_NOT_FOUND
INVALID_REPLAY_RANGE
CHECKPOINT_RECORDING_MISMATCH
CHECKPOINT_CORRUPTED
SBE_DECODE_FAILED
UNSUPPORTED_SCHEMA
UNSUPPORTED_TEMPLATE
SEQUENCE_GAP
NO_PROGRESS_TIMEOUT
MAXIMUM_REPLAY_DURATION
CHECKPOINT_WRITE_FAILED
COMPLETION_PROOF_WRITE_FAILED
COMPLETION_PROOF_ALREADY_EXISTS
VERIFICATION_MISMATCH
REPLAY_IMAGE_UNAVAILABLE
INTERNAL_ERROR
```

Review 时不要只检查异常 Message，应优先检查：

- 稳定 Failure Code；
- 关键上下文字段；
- Checkpoint 是否仍然安全；
- Proof 是否被错误创建；
- API 是否能直接诊断。

### Logs

生命周期事件：

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

MDC：

```text
jobId
attemptId
correlationId
recordingId
```

### Metrics

```text
replay.jobs
replay.duration
replay.events.applied
replay.duplicates
replay.sequence.gaps
replay.checkpoint.writes
replay.checkpoint.write.failures
replay.position.lag
replay.no.progress.timeouts
```

注意：Metrics 不使用 `jobId`、`checkpointKey` 等高基数 Label。

### 推荐阅读文件

```text
src/main/java/.../api/
src/main/java/.../failure/
src/main/java/.../observability/ReplayMetrics.java
src/test/java/.../api/ReplayControllerTest.java
src/test/java/.../observability/ReplayMetricsTest.java
```

---

# 5. 推荐学习顺序

## Phase 0：运行项目，先建立感性认识

预计时间：30–60 分钟。

执行：

```powershell
.\mvnw.cmd -ntp clean verify
.\scripts\demo-replay.ps1
```

观察：

- 测试是否全部通过；
- Demo 如何启动 Embedded Archive；
- 子 JVM 在 Sequence 400 后如何被强制终止；
- 恢复后第一条新事件是否为 401；
- 两次 Replay Digest 是否一致；
- Checkpoint 和 Completion Proof 文件内容。

完成标准：

- 能解释 Demo 的六个步骤；
- 能说明为什么 `Runtime.halt()` 比普通重启测试更有说服力。

---

## Phase 1：只读文档，建立系统边界

预计时间：1 小时。

阅读顺序：

```text
README.md
AGENTS.md
docs/architecture.md
docs/replay-six-point-review.md
docs/api.md
docs/operations.md
```

输出一页笔记：

```text
项目目标
项目非目标
核心 Invariants
完整 Workflow
关键持久化 Artifact
主要 Failure Scenarios
```

完成标准：

- 不看代码即可画出主架构图；
- 能准确区分 Replay Service 与上游 Archive Runtime。

---

## Phase 2：从 Domain 和 SBE 入手

预计时间：2–3 小时。

阅读顺序：

```text
domain/EventType.java
domain/Side.java
domain/MatchingEvent.java
domain/ReplayDigest.java
resources/sbe/matching-events.xml
codec/MatchingEventSbeEncoder.java
codec/MatchingEventSbeDispatcher.java
codec/MatchingEventSbeCodecTest.java
```

学习重点：

- Domain Event 如何映射到 SBE Template；
- Header Dispatch 如何选择 Decoder；
- v1/v2 兼容如何实现；
- Decoder 如何拒绝非法消息；
- Digest 为什么排除 `sourceId`。

完成标准：

- 能手工跟踪一条 `TRADE_CREATED` 的 Encode/Decode；
- 能指出 schema、template、version、block length 的检查位置；
- 能解释 future version 为什么必须 fail fast。

---

## Phase 3：掌握状态机、幂等和持久化

预计时间：2–3 小时。

阅读顺序：

```text
projection/ProjectionState.java
checkpoint/Checkpoint.java
checkpoint/AtomicPropertiesFile.java
checkpoint/CheckpointRepository.java
checkpoint/CompletionProof.java
checkpoint/CompletionProofRepository.java
```

配合测试：

```text
ProjectionStateTest
CheckpointRepositoryTest
CompletionProofRepositoryTest
```

学习重点：

- Duplicate 和 Gap 的处理；
- Position 推进时机；
- Checkpoint Atomic Replace；
- Proof Immutable Create；
- Attempt-specific History。

完成标准：

- 能回答“为什么 Duplicate 仍要推进 Position”；
- 能解释 Checkpoint 和 Proof 的完全不同职责；
- 能推演任意一个崩溃点的恢复结果。

---

## Phase 4：阅读 Replay 主链路

预计时间：3–4 小时。

阅读顺序：

```text
aeron/ReplayCommand.java
aeron/ReplayAttempt.java
aeron/ReplayProgress.java
aeron/ReplayResult.java
aeron/ReplayFragmentHandler.java
aeron/NoProgressWatchdog.java
aeron/AeronArchiveClientFactory.java
aeron/AeronReplayCoordinator.java
```

建议采用调用链 Review：

```text
AeronReplayCoordinator.replay()
    → inspect recording range
    → load checkpoint
    → validate replay range
    → create ProjectionState
    → create ReplayFragmentHandler
    → save initial recovery point
    → replayRange()
    → startReplay()
    → poll Subscription
    → FragmentAssembler
    → handler.onFragment()
    → SBE decode
    → ProjectionState.apply()
    → publish progress
    → periodic checkpoint
    → final checkpoint
    → verify expected values
    → write completion proof
```

完成标准：

- 能从 `ReplayCommand` 一直追踪到 Proof 文件；
- 能说明每个 Failure 在哪个层次产生；
- 能说明为什么使用 `FragmentAssembler`；
- 能说明 `Header.position()` 的准确语义。

---

## Phase 5：阅读应用层、API 和可观测性

预计时间：2 小时。

阅读顺序：

```text
application/ReplayJobManager.java
application/ReplayJobSnapshot.java
application/ReplayJobState.java
api/StartReplayRequest.java
api/ReplayController.java
api/ReplayJobResponse.java
api/ApiExceptionHandler.java
observability/ReplayMetrics.java
config/ReplayProperties.java
config/ReplayConfiguration.java
```

学习重点：

- Job State Transition；
- `checkpointKey` 并发冲突；
- Live Progress 更新；
- MDC 生命周期；
- Terminal Result 与 Failure 映射；
- Low-cardinality Metrics；
- Validation 与 HTTP Status。

完成标准：

- 能解释为什么 POST 返回 202；
- 能解释 `VERIFICATION_FAILED` 和 `FAILED` 的区别；
- 能解释为什么 Job History 可以在内存，而 Checkpoint 必须持久化。

---

## Phase 6：从测试反向 Review 设计

预计时间：3–4 小时。

按以下顺序阅读测试：

### 第一组：纯状态与 Codec

```text
ProjectionStateTest
MatchingEventSbeCodecTest
NoProgressWatchdogTest
```

### 第二组：持久化和应用层

```text
CheckpointRepositoryTest
CompletionProofRepositoryTest
ReplayJobManagerTest
ReplayControllerTest
ReplayMetricsTest
```

### 第三组：真实 Archive Integration

```text
ReplayFailureScenarioIntegrationTest
AeronReplayCoordinatorIntegrationTest
ReplayCoordinatorNoProgressIntegrationTest
EmbeddedArchiveFixture
CrashReplayProcessMain
```

每个测试回答四个问题：

1. 它证明了哪条 Invariant？
2. 它使用了 Mock、Fake 还是真实 Archive？
3. 测试失败时意味着哪种生产风险？
4. 它是否同时验证了结果和持久化状态？

完成标准：

- 能为每条核心 Invariant 找到对应测试；
- 能指出哪些是 Unit Test，哪些是 Integration Test；
- 能解释真实 Archive 测试比模拟文件读取更有价值的原因。

---

## Phase 7：独立完成一次完整 Review

预计时间：2–3 小时。

不要先看 `docs/replay-six-point-review.md` 的结论。

先按第 7 节 Review 流程独立检查，再与现有 Review 文档对比。

最终输出：

```text
Scope
Architecture
Invariants
Correctness Evidence
Crash Semantics
Failure Semantics
Operational Concerns
Test Coverage
Residual Risks
Final Verdict
```

---

# 6. 建议的五天学习计划

## Day 1：运行与架构

```text
运行 clean verify
运行 demo-replay
阅读 README、AGENTS、Architecture
画出系统图
```

交付物：

```text
一张 Replay Workflow 图
一页项目边界说明
```

## Day 2：SBE 与 Domain

```text
学习 SBE Schema
查看 Generated Codec
跟踪 Encode/Decode
理解 Schema Evolution
理解 Replay Digest
```

交付物：

```text
一张 SBE Message Header 字段表
一条事件完整 Encode/Decode 跟踪
```

## Day 3：Checkpoint 与 Replay Core

```text
阅读 ProjectionState
阅读 Checkpoint/Proof
阅读 FragmentHandler
阅读 Coordinator
推演 Duplicate、Gap、Crash
```

交付物：

```text
三种 Sequence 分支状态表
四个崩溃点恢复推演
```

## Day 4：Job、API、Metrics 与 Tests

```text
阅读 JobManager
阅读 API
阅读 Failure Model
阅读 Metrics
逐个映射 Integration Test
```

交付物：

```text
Job State Diagram
Failure Code Matrix
Invariant → Test Mapping
```

## Day 5：独立 Review 与讲解

```text
执行完整 Review
写 Review Report
进行 15 分钟口头讲解
回答自测题
```

交付物：

```text
最终 Review 报告
15 分钟项目讲稿
```

---

# 7. 标准 Code Review 流程

下面这套流程既适用于当前仓库，也可以复用到其他 Replay、CDC、Event Sourcing 和 Projection Recovery 项目。

## Step 1：确认 Scope 和 Non-goals

检查：

- 项目究竟 Replay 的是 Command 还是 Event？
- 是否重新执行业务决策？
- 是否仅重建 Projection？
- 是否恢复完整业务状态？
- 是否明确上游和下游职责？
- README 是否存在夸大描述？

当前项目应得出的结论：

```text
Replay 的是匹配事件流
不重新执行撮合
不恢复 OrderBook
重点是 Replay Workflow 和 Recovery Correctness
```

---

## Step 2：检查 Build 和 Generated Code

检查：

- JDK 和 Maven 版本是否锁定；
- Maven Wrapper 是否可用；
- `clean verify` 是否从零通过；
- SBE Code 是否在 `generate-sources` 生成；
- Generated Code 是否错误提交到仓库；
- Schema 是否有唯一来源；
- CI 是否运行同一条验证命令。

命令：

```powershell
.\mvnw.cmd --version
.\mvnw.cmd -ntp clean verify
```

PASS 标准：

```text
删除 target 后仍能生成、编译和测试通过
```

---

## Step 3：检查协议和数据契约

检查：

- Schema ID 是否固定；
- Template ID 是否唯一；
- Header 字段是否全部验证；
- 是否拒绝未来 Version；
- 是否支持需要保留的历史 Version；
- `actingBlockLength` 是否按 Version 验证；
- 未知 Template 和未知 Schema 是否区分；
- Enum 未知值是否 fail fast；
- Decode 失败是否不会推进 Checkpoint。

重点文件：

```text
matching-events.xml
MatchingEventSbeDispatcher.java
MatchingEventSbeCodecTest.java
```

---

## Step 4：检查 Replay Boundary

检查：

- 是否显式指定 `recordingId`；
- 是否拒绝不明确的 Recording；
- Stop Position 是否只捕获一次；
- Stop Position 是否落在合法 Recording Range；
- Live Recording 后续追加数据是否被排除；
- Replay Length 是否正确计算；
- 最终 Position 是否精确到达边界。

PASS 证据：

```text
boundedReplayDoesNotFollowEventsAppendedAfterCapturedStopPosition
```

---

## Step 5：检查 Position 与 Sequence 语义

检查：

- 是否明确区分 Position 和 Sequence；
- Checkpoint 是否保存 `Header.position()`；
- 保存的是 Fragment End Position 还是 Start Position；
- Duplicate 是否推进 Position；
- Gap 是否停止；
- Decode Failure 是否不推进；
- Position 是否单调递增；
- Position 是否不会超过 Stop Position。

这是整个 Review 中优先级最高的一步。

---

## Step 6：检查幂等和 Gap Detection

建立状态表：

| 输入 Sequence | 行为 | Digest | Applied Count | Position |
|---|---|---:|---:|---:|
| `< last` | Duplicate | 不变 | 不变 | 推进 |
| `= last` | Duplicate | 不变 | 不变 | 推进 |
| `= last + 1` | Apply | 更新 | +1 | 推进 |
| `> last + 1` | Gap Failure | 不变 | 不变 | 不越过错误消息 |

检查代码和测试是否严格一致。

重点文件：

```text
ProjectionState.java
ReplayFragmentHandler.java
ProjectionStateTest.java
ReplayFailureScenarioIntegrationTest.java
```

---

## Step 7：检查 Checkpoint Crash Consistency

检查：

- Checkpoint 是否先写临时文件；
- 是否 Force 文件内容；
- 是否同文件系统 Atomic Move；
- Atomic Move 不支持时是否显式失败；
- 路径是否防止 Traversal；
- 损坏文件是否返回结构化 Failure；
- Checkpoint 是否只保存完整处理后的状态；
- 周期性保存是否包括 Duplicate 消息。

重点推演以下崩溃点：

```text
A. Decode 前崩溃
B. Apply 后、Checkpoint 前崩溃
C. Temp File 写入中崩溃
D. Atomic Move 后崩溃
E. Final Checkpoint 后、Proof 前崩溃
```

期望：

```text
A：重放当前消息
B：重放当前消息，依靠业务幂等处理
C：读取旧 Checkpoint
D：读取新 Checkpoint
E：进度保留，但需要重新验证并创建新的 Proof
```

---

## Step 8：检查 Completion Proof

检查：

- Proof 是否只在 Verification 成功后创建；
- Verification Failure 是否不创建 Proof；
- Proof 是否按 Attempt 保存；
- 是否不可覆盖；
- No-op Replay 是否有独立 Proof；
- Proof 是否包含 Replay Range 和 Resume Flag；
- Proof 写失败是否返回明确 Failure。

重点文件：

```text
CompletionProofRepository.java
CompletionProofRepositoryTest.java
ReplayFailureScenarioIntegrationTest.java
```

---

## Step 9：检查 Timeout 和 Stalled Replay

检查：

- 是否错误使用单一绝对 Timeout；
- Position 推进是否刷新 Watchdog；
- 使用的是否是 Monotonic Clock；
- No-progress Failure 是否有诊断字段；
- 最大 Replay Duration 是否独立；
- Timeout 后 Checkpoint 是否仍停在最后正确位置；
- Timeout 后是否不会创建 Completion Proof。

PASS 证据：

```text
continuouslyAdvancingReplayCanRunLongerThanNoProgressTimeout
stalledReplayReturnsNoProgressTimeout
```

---

## Step 10：检查异步任务与并发

检查：

- Job State Transition 是否明确；
- 同一个 `checkpointKey` 是否互斥；
- Active Key 是否在所有终态正确释放；
- Executor Capacity Failure 是否正确处理；
- Progress Snapshot 是否线程安全；
- Job Result 和 Failure 是否互斥；
- Terminal State 是否不可回退；
- Job ID 与 Attempt ID 是否区分。

重点文件：

```text
ReplayJobManager.java
ReplayJobSnapshot.java
ReplayJobManagerTest.java
```

---

## Step 11：检查 API 和 Failure Contract

检查：

- Required Fields 是否验证；
- Digest 是否使用无符号字符串表达；
- POST 是否返回 202 和 Location；
- Missing Job 是否使用正确 HTTP Status；
- Active Key Conflict 是否返回 409；
- Failure 是否有稳定 Code；
- Failure 是否包含足够 Context；
- API 是否避免依赖异常字符串解析。

---

## Step 12：检查 Observability

### Logs

检查：

- 每个生命周期日志是否有 `jobId`；
- 是否包含 `attemptId` 和 `correlationId`；
- 是否记录 Boundary、Checkpoint 和 Terminal Result；
- 是否避免逐事件日志；
- Failure 是否记录 Code 和 Position。

### Metrics

检查：

- Metrics 是否低基数；
- 是否有 Replay Duration；
- 是否有 Position Lag；
- 是否有 Applied、Duplicate、Gap；
- 是否有 Checkpoint Success/Failure；
- 是否有 No-progress Timeout；
- Active Job 结束后 Gauge State 是否清理。

---

## Step 13：检查真实故障测试

Review 时把测试按证据强度分级：

### Level 1：Unit Test

```text
ProjectionState
Digest
Codec Validation
Watchdog
Repository
```

### Level 2：Application Integration

```text
Job Manager
Controller
Metrics
Coordinator with controlled clock
```

### Level 3：Real Archive Integration

```text
Embedded Media Driver
Archive Recording
Archive Replay
Live Recording Boundary
Invalid Fragment
Sequence Gap
```

### Level 4：Process Crash

```text
Child JVM
Runtime.halt(77)
Fresh Coordinator
Resume from persisted Position
Final Digest comparison
```

最高价值证据：

```text
hardCrashResumesFromLastPersistedAeronPosition
```

---

## Step 14：检查外部 Projection 边界

当前 `ProjectionState` 在内存中，因此：

```text
State Update
Checkpoint
```

可以在一个简单恢复模型中验证。

一旦替换成数据库或远程副作用，需要重新 Review：

```text
Business Effect
Deduplication / Inbox
Checkpoint
```

是否处于同一个事务性恢复单元。

必须指出：

> 文件 Checkpoint 无法自动让独立数据库写入获得 Exactly-once。

---

## Step 15：输出 Review 结论

推荐格式：

```markdown
## Scope

## Architecture Summary

## Core Invariants

## Correctness Findings

## Crash Recovery Findings

## Failure Semantics

## Test Evidence

## Operational Findings

## Residual Risks

## Final Verdict
```

评级建议：

```text
PASS
PASS WITH MINOR IMPROVEMENTS
NEEDS CHANGES
FAIL
```

每一条结论必须包含：

```text
Finding
Evidence
Risk
Recommended Action
```

---

# 8. Review 检查表

## 8.1 构建

```text
[ ] Java 21 环境正确
[ ] Maven Wrapper 可运行
[ ] clean verify 通过
[ ] SBE Codec 可从零生成
[ ] CI 使用同一验证命令
```

## 8.2 Replay 边界

```text
[ ] recordingId 必填
[ ] Recording Range 已验证
[ ] stopPosition 捕获一次
[ ] Live Appended Events 被排除
[ ] Replay 精确到达 Boundary
```

## 8.3 Position 与 Sequence

```text
[ ] Position 和 Sequence 分离
[ ] 保存 Header.position()
[ ] Duplicate 推进 Position
[ ] Gap 不越过错误 Fragment
[ ] Decode Failure 不推进 Checkpoint
```

## 8.4 Checkpoint 与 Proof

```text
[ ] Checkpoint 原子替换
[ ] Path Traversal 被拒绝
[ ] Proof 只在验证成功后创建
[ ] Proof 按 Attempt 独立保存
[ ] Proof 不可覆盖
[ ] Verification Failure 保留 Progress 但无 Proof
```

## 8.5 Timeout

```text
[ ] No-progress 基于 Position
[ ] 使用 Monotonic Clock
[ ] Healthy Replay 可超过 No-progress Timeout
[ ] Absolute Maximum Duration 独立
[ ] Timeout 保留最后正确 Checkpoint
```

## 8.6 API 与并发

```text
[ ] POST 返回 202
[ ] Job 状态机清晰
[ ] 同 checkpointKey 互斥
[ ] Progress 线程安全
[ ] Failure Code 稳定
[ ] Result 与 Failure 语义互斥
```

## 8.7 Observability

```text
[ ] 生命周期日志完整
[ ] MDC 字段齐全
[ ] 无逐事件日志
[ ] Metrics 低基数
[ ] Position Lag 可观察
[ ] Checkpoint Failure 可观察
```

## 8.8 Tests

```text
[ ] Codec v1/v2
[ ] Future Schema Failure
[ ] Invalid Block Length
[ ] Duplicate
[ ] Sequence Gap
[ ] Live Bounded Replay
[ ] Verification Mismatch
[ ] Immutable Proof
[ ] No-progress Timeout
[ ] Hard Process Crash
```

---

# 9. 源码阅读导航

## 第一层：项目意图

```text
README.md
AGENTS.md
docs/architecture.md
```

## 第二层：数据协议

```text
src/main/resources/sbe/matching-events.xml
src/main/java/.../domain/
src/main/java/.../codec/
```

## 第三层：状态与持久化

```text
src/main/java/.../projection/
src/main/java/.../checkpoint/
```

## 第四层：Replay Engine

```text
src/main/java/.../aeron/
```

## 第五层：应用与接口

```text
src/main/java/.../application/
src/main/java/.../api/
src/main/java/.../config/
src/main/java/.../observability/
```

## 第六层：测试证据

```text
src/test/java/.../projection/
src/test/java/.../codec/
src/test/java/.../checkpoint/
src/test/java/.../application/
src/test/java/.../aeron/
src/test/java/.../support/
```

---

# 10. 必做练习

## 练习 1：跟踪正常 Replay

选择 10 条事件，记录：

```text
Sequence
Aeron Position
Digest Before
Digest After
Applied Count
Checkpoint Position
```

目标：理解业务 Sequence、Transport Position 和 Digest 的同步变化。

---

## 练习 2：插入 Duplicate

构造：

```text
1, 2, 3, 3, 4
```

预测并验证：

- 第二个 3 是否改变 Digest；
- Applied Count；
- Duplicate Count；
- Position；
- 最终 Checkpoint。

---

## 练习 3：插入 Gap

构造：

```text
1, 2, 4
```

预测并验证：

- Failure Code；
- 最终 Sequence；
- Checkpoint Position；
- 是否创建 Completion Proof。

---

## 练习 4：Live Bounded Replay

执行：

```text
发布 1–5
捕获 stopPosition
发布 6–10
Replay 到捕获边界
```

验证：

```text
最终 Sequence = 5
Digest = events 1–5
Recording 仍包含 6–10
```

---

## 练习 5：Crash Point 推演

分别假设进程在以下位置崩溃：

```text
SBE Decode 前
Digest Update 后
Checkpoint Temp File 写入中
Checkpoint Atomic Move 后
Final Checkpoint 后
Completion Proof 创建后
```

为每个点写出：

```text
重启起点
是否重复处理
是否丢事件
Checkpoint 内容
Proof 状态
```

---

## 练习 6：增加一个新 SBE Version

假设 v3 新增：

```text
recoverySource
```

设计：

- `sinceVersion`；
- v1/v2 默认值；
- v3 Block Length；
- Digest 是否包含；
- Future Version 策略；
- 需要新增的测试。

不要直接改代码，先写兼容性设计。

---

## 练习 7：替换成数据库 Projection

假设 `ProjectionState.apply()` 改为写 PostgreSQL。

回答：

1. DB Commit 成功、Checkpoint 失败怎么办？
2. Checkpoint 成功、DB Commit 失败怎么办？
3. Inbox 表放在哪里？
4. Business Effect 和 Offset 如何处于同一事务？
5. Duplicate 如何避免重复副作用？

详细设计见：

```text
learning/lab-07-postgres-projection-transaction-design.md
```

---

# 11. 面试与 Review 自测题

## 基础题

1. Aeron Archive Replay 和普通文件顺序读取有什么区别？
2. Recording ID 为什么必须显式传入？
3. 为什么 Replay 要有 Stop Position？
4. `Header.position()` 表示 Fragment 的开始还是结束？
5. 为什么 Duplicate 事件仍然需要保存新的 Position？
6. 为什么 Gap 不能被当作“暂时没有数据”？
7. Replay Digest 和 State Hash 有什么区别？
8. Completion Proof 为什么不能和 Checkpoint 放在同一个语义里？

## 进阶题

9. 如果 Checkpoint 在业务应用之前写入，会发生什么？
10. 如果业务应用完成但 Checkpoint 未写，如何恢复？
11. 为什么 No-progress Timeout 比固定 20 秒总 Timeout 更合理？
12. 为什么测试 Timeout 要注入 Monotonic Clock？
13. `actingVersion` 和当前 Schema Version 不同是否一定是错误？
14. 为什么 `actingBlockLength` 也必须验证？
15. 为什么 `sourceId` 被排除在 Digest 之外？
16. 为什么 Metrics 不能使用 `jobId` 作为 Label？
17. 为什么 Verification Failure 仍然保留最终 Checkpoint？
18. 为什么 No-op Replay 仍然需要独立 Completion Proof？

## 架构题

19. 如何把当前项目扩展为多个 Projection？
20. 如何支持数据库 Transactional Inbox？
21. 如何支持 Snapshot + Tail Replay？
22. 如何处理 Archive Recording Retention？
23. 如何在多实例服务中保护同一个 `checkpointKey`？
24. 如何验证 Replay 不会追赶 Live Stream？
25. 如何证明 Hard Crash 后没有丢事件？
26. 如何区分 Aeron Accepted、Archive Recorded、Disk Durable 和 Replicated Commit？

---

# 12. 15 分钟项目讲解模板

## 第 1–2 分钟：问题和定位

```text
这个项目不恢复撮合引擎，而是展示如何可靠地 Replay
一条 SBE 编码的匹配事件流，并在进程崩溃后安全恢复。
```

## 第 3–5 分钟：核心模型

```text
recordingId
stopPosition
eventSequence
Aeron Position
Replay Digest
Checkpoint
Completion Proof
```

## 第 6–9 分钟：主流程

```text
Capture Boundary
Load Checkpoint
Start Archive Replay
Decode SBE
Validate Sequence
Apply Digest
Save Position
Resume After Crash
Verify Result
Write Proof
```

## 第 10–12 分钟：Correctness

```text
Duplicate Idempotency
Gap Detection
Atomic Checkpoint
No-progress Timeout
Schema Evolution
Immutable Proof
```

## 第 13–15 分钟：测试证据

```text
Real Embedded Archive
Live Bounded Replay
Invalid SBE
Verification Mismatch
Runtime.halt Child JVM
Resumed Digest == Uninterrupted Digest
```

结束语：

> 这个仓库的价值不是业务功能复杂，而是它用一套最小模型，把 Replay 的边界、状态、失败语义、崩溃恢复和可验证性完整串联起来。

---

# 13. 最终掌握标准

达到以下标准，才算真正学会该项目：

```text
[ ] 不看 README 可以画出完整 Workflow
[ ] 能区分 Position、Sequence、Digest 和 Checkpoint
[ ] 能解释 SBE v1/v2 兼容策略
[ ] 能跟踪一条 Fragment 从 Archive 到 Proof
[ ] 能推演所有主要崩溃点
[ ] 能解释 Duplicate 和 Gap 的不同处理
[ ] 能独立 Review Atomic Checkpoint
[ ] 能解释 No-progress Watchdog
[ ] 能为每条 Invariant 找到对应测试
[ ] 能完成一次结构化 Review 报告
[ ] 能进行 15 分钟项目讲解
```

完成以上内容后，可以把本仓库作为以下主题的实践参考：

- Event Stream Replay；
- Projection Recovery；
- Event Sourcing Recovery；
- CDC Consumer Checkpoint；
- Message Consumer Idempotency；
- Crash Consistency；
- Binary Protocol Evolution；
- Recovery Verification；
- SRE Failure-oriented Testing。
