# Lab 05：Crash Point 推演与硬崩溃验证

本 Lab 分析 Replay 在不同执行阶段突然崩溃时会发生什么，并使用真实的子 JVM `Runtime.halt()` 测试验证核心恢复路径。

## 1. 运行真实硬崩溃测试

执行：

```powershell
.\mvnw.cmd -ntp '-Dtest=AeronReplayCoordinatorIntegrationTest#hardCrashResumesFromLastPersistedAeronPosition' test
```

成功标准：

```text
Tests run: 1, Failures: 0, Errors: 0
BUILD SUCCESS
```

本次实际输出：

```text
[1/6] Started embedded Aeron Archive
[2/6] Recorded 1,000 SBE events
[3/6] Completed uninterrupted replay
[4/6] Halted replay process after checkpoint sequence 400
[5/6] Resumed from saved Aeron position
[6/6] Final replay digest matched uninterrupted replay
recordingId=0
replayStartPosition=41600
boundedStopPosition=104000
crashCheckpointPosition=41600
crashCheckpointSequence=400
firstEventSequenceAfterResume=401
finalEventSequence=1000
uninterruptedReplayDigest=16377054453689728713
resumedReplayDigest=16377054453689728713
appliedEventsTotal=1000
duplicatesThisRun=0
verificationPassed=true
```

这个测试不是优雅停止：子 JVM 使用 `Runtime.getRuntime().halt(77)`，不会执行普通 Shutdown Hook。

## 2. 六个 Crash Point 总览

| Crash Point | 磁盘上可信的 Checkpoint | 重启行为 | 是否丢事件 | 是否可能重新应用 |
|---|---|---|---|---|
| SBE Decode 前 | 上一次成功保存的位置 | 从旧 Checkpoint 重新读取当前事件 | 否 | 当前事件会重新读取 |
| Digest Update 后 | 通常仍是旧 Checkpoint | 丢弃崩溃进程的内存状态，从旧 Checkpoint 重放 | 否 | 会重新应用未保存窗口 |
| Checkpoint Temp File 写入中 | 旧目标文件 | 忽略不完整临时文件，从旧目标恢复 | 否 | 未提交窗口会重放 |
| Checkpoint Atomic Move 后 | 新目标文件 | 从新 Checkpoint 恢复 | 否 | 不需要重放已提交窗口 |
| Final Checkpoint 后、Proof 前 | Replay 边界的最终 Checkpoint | 以边界为起点执行 no-op 验证并重新创建 Proof | 否 | 不需要重放业务事件 |
| Completion Proof 创建后 | 最终 Checkpoint 和不可变 Proof 都存在 | 读取既有证据或使用新 Attempt 做 no-op 验证 | 否 | 不需要重放业务事件 |

这里的“重新应用”表示进程崩溃前已经在内存中处理过、但还没有进入持久化 Checkpoint 的事件。它不会造成事件丢失，但外部 Projection 必须具备幂等性，才能安全承受重复业务副作用。

## 3. Crash Point 1：SBE Decode 前

处理顺序还没有进入业务状态：

```text
读取 Fragment
    ↓
进程在 Decode 前崩溃
```

此时：

```text
Digest 没变
lastAppliedEventSequence 没变
lastAppliedAeronPosition 没变
Checkpoint 没变
```

重启后从最后一个有效 Checkpoint 开始，重新读取这个 Fragment。因为它之前没有产生业务应用，所以不会丢事件，也不会产生业务重复副作用。

## 4. Crash Point 2：Digest Update 后

`ProjectionState.apply()` 会在内存中更新：

```text
replayDigest
lastAppliedEventSequence
lastAppliedAeronPosition
appliedEventsTotal
```

如果刚更新 Digest 就崩溃：

```text
进程内存状态：已经前进
磁盘 Checkpoint：可能仍停在上一条已保存事件
```

重启时只读取磁盘 Checkpoint，不会相信已经消失的进程内存，因此会重新处理这个事件。

这是预期的恢复模型：

```text
不丢事件
允许重放未 Checkpoint 的窗口
需要 Projection 对重复业务应用保持幂等
```

本项目的 `ReplayDigest` 只在内存中更新，最终以 Checkpoint 中保存的 Digest 为准。

## 5. Crash Point 3：Checkpoint Temp File 写入中

Checkpoint 写入不是直接覆盖目标文件，而是：

```text
写入 *.tmp
    ↓
FileChannel.force(true)
    ↓
Atomic Move 替换正式文件
```

如果在临时文件写入过程中崩溃：

```text
正式 Checkpoint 文件仍是旧版本
临时文件可能不完整或残留
```

重启读取正式目标文件，使用旧 Checkpoint 恢复。临时文件没有成为可见的正式状态，因此不会读取半截 Properties。

注意：这讨论的是进程崩溃。当前实现明确要求 Atomic Move；它不额外保证父目录 `fsync` 或存储设备掉电后的复制语义。

## 6. Crash Point 4：Checkpoint Atomic Move 后

如果崩溃发生在 Atomic Move 已经完成之后：

```text
临时文件已经成为正式 Checkpoint
正式文件内容是完整的新版本
```

重启会读取新 Checkpoint：

```text
从新 Position 继续
不重复已提交的事件
```

这正是原子替换的价值：Checkpoint 的可见结果只能是旧版本或新版本，不应该是两个版本拼接的损坏文件。

实现位置：

```text
src/main/java/io/github/mikuwwl/matchingreplay/checkpoint/AtomicPropertiesFile.java
```

核心操作：

```java
channel.force(true);
Files.move(
    temporary,
    normalized,
    StandardCopyOption.ATOMIC_MOVE,
    StandardCopyOption.REPLACE_EXISTING);
```

## 7. Crash Point 5：Final Checkpoint 后、Completion Proof 前

Replay 完整到达边界后，Coordinator 会先保存最终 Checkpoint，再进行最终验证和 Proof 写入：

```text
Replay 到达 stopPosition
    ↓
handler.save()       保存最终 Checkpoint
    ↓
计算 verification
    ↓
写 Completion Proof
```

如果在最终 Checkpoint 之后、Proof 之前崩溃：

```text
Progress Checkpoint 已经到达 Replay 边界
Completion Proof 尚未存在
```

重启时：

```text
replayStartPosition == replayStopPosition
没有需要重新读取的事件
执行 no-op 验证
生成新的 Completion Proof
```

因此不会重新应用业务事件，也不会把“有进度”错误地当成“已验证成功”。

## 8. Crash Point 6：Completion Proof 创建后

Proof 写入使用不可变创建：

```text
写临时文件并 force
    ↓
创建 Hard Link 暴露正式文件
    ↓
删除临时文件
```

如果 Proof 已经创建成功后进程崩溃：

```text
最终 Checkpoint 存在
不可变 Completion Proof 存在
```

Proof 绑定：

```text
jobId
attemptId
recordingId
replayStartPosition
replayStopPosition
finalEventSequence
finalReplayDigest
```

同一个 `attemptId` 再次写入时，会得到：

```text
COMPLETION_PROOF_ALREADY_EXISTS
```

这是保护证据不可覆盖，而不是 Replay 失败。新的 Replay Attempt 使用新的 `attemptId`，可以执行 no-op 验证并生成另一份独立 Proof。

## 9. 真实硬崩溃结果解读

本次测试在 Sequence 400 的 Checkpoint 保存后强制终止子 JVM：

```text
crashCheckpointSequence = 400
crashCheckpointPosition = 41600
```

新进程恢复时：

```text
firstEventSequenceAfterResume = 401
appliedEventsThisRun          = 600
finalEventSequence            = 1000
```

不中断 Replay 和恢复 Replay 得到完全相同的 Digest：

```text
uninterruptedReplayDigest = 16377054453689728713
resumedReplayDigest       = 16377054453689728713
```

最终：

```text
appliedEventsTotal = 1000
duplicatesThisRun  = 0
verificationPassed = true
```

这证明：

```text
硬崩溃后从最后持久化 Position 恢复
没有丢失 Sequence 401～1000
恢复结果与不中断 Replay 一致
最终 Verification 成功
```

## 10. 对应源码和测试

Replay 主循环：

```text
src/main/java/io/github/mikuwwl/matchingreplay/aeron/AeronReplayCoordinator.java
src/main/java/io/github/mikuwwl/matchingreplay/aeron/ReplayFragmentHandler.java
```

Checkpoint 原子写入：

```text
src/main/java/io/github/mikuwwl/matchingreplay/checkpoint/AtomicPropertiesFile.java
src/main/java/io/github/mikuwwl/matchingreplay/checkpoint/CheckpointRepository.java
```

不可变 Proof：

```text
src/main/java/io/github/mikuwwl/matchingreplay/checkpoint/CompletionProofRepository.java
```

硬崩溃子进程：

```text
src/test/java/io/github/mikuwwl/matchingreplay/support/CrashReplayProcessMain.java
```

集成测试：

```text
src/test/java/io/github/mikuwwl/matchingreplay/aeron/AeronReplayCoordinatorIntegrationTest.java
```

## 11. 本 Lab 的最终结论

```text
Checkpoint 之前的内存状态      崩溃后会丢弃并重放
Atomic Move 之后的 Checkpoint  崩溃后可以读取新版本
Final Checkpoint 无 Proof       重启后 no-op 验证并补建 Proof
Proof 创建成功                  证据不可覆盖
```

一句话记忆：

> Replay 的恢复依据是最后一个完整、原子保存的 Checkpoint；未保存的进度可以重放，但不能被跳过。

## 12. 联合验证

本 Lab 还联合运行了 Checkpoint 和 Completion Proof 测试：

```powershell
.\mvnw.cmd -ntp '-Dtest=AeronReplayCoordinatorIntegrationTest#hardCrashResumesFromLastPersistedAeronPosition,CheckpointRepositoryTest,CompletionProofRepositoryTest' test
```

结果：

```text
Tests run: 6, Failures: 0, Errors: 0
BUILD SUCCESS
```

下一步 Lab：增加一个新的 SBE Version，设计向后兼容和 Future Version 拒绝策略。
