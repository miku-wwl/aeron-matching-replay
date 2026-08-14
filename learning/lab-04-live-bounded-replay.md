# Lab 04：Live Bounded Replay

本 Lab 验证一个非常重要的 Replay 边界规则：

```text
Recording 还在继续增长
Replay 仍然只处理捕获 stopPosition 之前的事件
```

## 1. 运行 Lab

执行已有的真实 Aeron Archive 集成测试：

```powershell
.\mvnw.cmd -ntp '-Dtest=ReplayFailureScenarioIntegrationTest#boundedReplayDoesNotFollowEventsAppendedAfterCapturedStopPosition' test
```

成功标准：

```text
Tests run: 1, Failures: 0, Errors: 0
BUILD SUCCESS
```

对应测试：

```text
src/test/java/io/github/mikuwwl/matchingreplay/aeron/ReplayFailureScenarioIntegrationTest.java
```

## 2. 实验步骤

测试按以下顺序操作：

```text
1. 启动真实的 Embedded Archive
2. 开始 Live Recording
3. 发布 Sequence 1～5
4. 捕获当前 stopPosition
5. 继续发布 Sequence 6～10
6. 使用第 4 步捕获的边界执行 Replay
7. 验证 Replay 只处理 1～5
```

可以表示为：

```text
时间 ───────────────────────────────────────→

发布：  1   2   3   4   5 | 6   7   8   9   10
                          ↑
                    捕获 stopPosition

Replay 范围：        [1   2   3   4   5]
```

## 3. 当前 Fixture 中的典型位置

本项目的测试事件编码长度固定时，位置通常表现为：

```text
Sequence 1～5   → 128, 256, 384, 512, 640
捕获边界        → stopPosition = 640
Sequence 6～10  → 768, 896, 1024, 1152, 1280
```

真实断言使用 Archive 返回的边界和 Digest，而不是把这些数字写死。

## 4. 验证结果

测试确认：

```text
finalEventSequence       = 5
appliedEventsThisRun     = 5
replayStopPosition       = 捕获的 stopPosition
finalReplayDigest        = 1～5 的 Digest
Completion Proof         = 成功创建，绑定捕获的 Replay 边界
Recording                = 仍然处于 Live 状态
```

最关键的一点是：

```text
Recording 中已经存在 Sequence 6～10
但本次 Replay 不会应用它们
```

## 5. 为什么不能 Replay 到当前最新位置？

如果 Replay 一直追着 Recording 的实时末尾走：

```text
Replay 处理到 5
    ↓
上游追加 6
    ↓
Replay 继续处理 6
    ↓
上游追加 7
    ↓
Replay 继续处理 7
    ↓
Replay 没有稳定终点
```

这种模式无法得到清晰的完成边界，也无法可靠地计算“本次 Replay 到底验证了哪些事件”。

Bounded Replay 通过固定 `stopPosition` 把本次工作集冻结下来：

```text
本次 Replay：处理到捕获边界
下一次 Replay：从 Checkpoint 继续处理后续事件
```

## 6. Position 边界和业务 Sequence 边界

Replay 的实际终止条件是 Aeron Position：

```text
currentPosition >= stopPosition
```

业务 Sequence 只是验证结果：

```text
最终 Sequence 应该是 5
```

两者分工不同：

```text
stopPosition       决定 Replay 读到哪里
expectedSequence   验证边界内业务事件是否完整
expectedDigest     验证边界内处理结果是否正确
```

不能只用 Sequence 作为 Replay 边界，因为 Aeron Archive 按 Transport Position 读取，而不是按业务字段查找事件。

## 7. Completion Proof 为什么包含边界？

本次 Replay 成功后，Completion Proof 绑定：

```text
recordingId
replayStartPosition
replayStopPosition
finalEventSequence
finalReplayDigest
```

这样以后查看 Proof 时，可以明确知道：

```text
这次验证的是哪一条 Recording
从哪里开始
到哪里结束
验证到了哪个业务 Sequence
得到什么 Digest
```

如果不保存 `stopPosition`，后续无法证明 Sequence 5 是当时的边界结果，还是 Replay 过程中顺便处理了后来追加的 Sequence 6～10。

## 8. 对应源码

Live Recording 和边界捕获：

```text
src/test/java/io/github/mikuwwl/matchingreplay/support/EmbeddedArchiveFixture.java
```

重点方法：

```java
live.captureBoundary();
```

Replay 边界控制：

```text
src/main/java/io/github/mikuwwl/matchingreplay/aeron/AeronReplayCoordinator.java
src/main/java/io/github/mikuwwl/matchingreplay/aeron/ReplayCommand.java
```

验证测试：

```text
src/test/java/io/github/mikuwwl/matchingreplay/aeron/ReplayFailureScenarioIntegrationTest.java
```

## 9. 本 Lab 的最终结论

```text
Capture stopPosition
    → 之后允许 Recording 继续增长
    → Replay 只读取捕获边界之前的 Fragment
    → 到达边界后完成本次 Replay
    → 新追加事件留给下一次 Replay
```

一句话记忆：

> Live Recording 可以继续写，但一次 Replay 必须有固定的终点。

下一步 Lab：推演 Crash Point，分别分析进程在 Decode、Digest、Checkpoint 和 Proof 不同阶段崩溃时，重启会从哪里继续。
