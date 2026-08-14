# Lab 03：Sequence Gap 如何停止 Replay

本 Lab 构造事件序列：

```text
1, 2, 4
```

Sequence `3` 缺失，因此当 Replay 收到 `4` 时，必须失败，而不能跳过缺失事件继续执行。

## 1. 运行 Lab

本 Lab 使用已有的真实 Aeron Archive Integration Test：

```powershell
.\mvnw.cmd -ntp '-Dtest=ReplayFailureScenarioIntegrationTest#gapLeavesCheckpointAtLastGoodMessage' test
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

测试使用真实的嵌入式 Media Driver、Aeron Archive 和 Replay Coordinator，不是只调用一个普通方法模拟结果。

## 2. 本次场景

```text
事件 1：正常
事件 2：正常
事件 4：发现缺口，失败
```

测试设置每处理一条消息就保存一次 Checkpoint：

```java
properties.setCheckpointEveryProcessedMessages(1);
```

因此：

```text
处理完 1 → 保存 Checkpoint
处理完 2 → 保存 Checkpoint
处理 4   → Sequence Gap，不能保存
```

## 3. 验证结果

测试断言的关键结果如下：

```text
Failure Code              = SEQUENCE_GAP
lastAppliedEventSequence  = 2
receivedEventSequence     = 4
Checkpoint Sequence       = 2
Completion Proof          = 不存在
```

在当前测试 Fixture 中，三条事件的典型位置是：

```text
Sequence 1 → Position 128
Sequence 2 → Position 256
Sequence 4 → Position 384
```

最终 Checkpoint 保留在 Sequence 2 对应的位置：

```text
Checkpoint Position = 256
```

真正的断言使用 Recording 返回的事件结束位置，而不是把 `256` 写死，因此即使 Fixture 的编码布局变化，测试仍然表达同一个语义。

## 4. 为什么 Sequence 4 不能直接应用？

当前状态是：

```text
lastAppliedEventSequence = 2
```

正常下一个事件必须满足：

```text
eventSequence == lastAppliedEventSequence + 1
```

也就是：

```text
4 == 2 + 1   → false
```

因此 `4` 是 Gap，不是普通的“未来事件”。代码会创建结构化的 `SEQUENCE_GAP` Failure，并停止 Replay。

## 5. Gap 事件发生了什么？

收到 Sequence 4 时，系统知道它所在的 Fragment Position，但不会把这个无效事件应用到业务状态：

```text
已应用 Sequence       = 2
已应用 Position       = Sequence 2 的 Position
收到的错误 Sequence   = 4
错误 Fragment Position = Sequence 4 的 Position
```

这两个位置不能混淆：

```text
Checkpoint Position       最后一个有效事件的位置
Failure Fragment Position 当前发现错误的位置
```

## 6. 为什么 Checkpoint 停在 Sequence 2？

Checkpoint 的推进顺序必须是：

```text
完整读取 Fragment
  → 解码成功
  → Schema 校验成功
  → Sequence 校验成功
  → 应用业务事件
  → 保存 Checkpoint
```

Sequence 4 在“Sequence 校验”阶段就失败了，所以不能执行后面的业务应用和 Checkpoint 保存。

如果错误事件之后仍然保存 Position：

```text
重启 Replay
    ↓
从错误事件之后开始
    ↓
Sequence 3 永远被跳过
```

这会把数据缺口永久隐藏掉，因此必须停在最后一个有效 Checkpoint。

## 7. 为什么不创建 Completion Proof？

Completion Proof 只代表：

```text
Replay 到达边界
最终 Sequence 正确
最终 Digest 正确
```

本次 Replay 在 Sequence 4 处提前失败：

```text
没有到达 Replay 边界
没有完成最终验证
```

因此：

```text
CompletionProofRepository.findByCheckpointKey("gap")
    = empty
```

失败只保留 Progress Checkpoint，方便后续排查或修复数据后重试；不会伪造成功证明。

## 8. 对应源码

Sequence 校验和 Gap Failure：

```text
src/main/java/io/github/mikuwwl/matchingreplay/projection/ProjectionState.java
```

Replay Coordinator 对 Failure 的处理：

```text
src/main/java/io/github/mikuwwl/matchingreplay/aeron/AeronReplayCoordinator.java
```

Failure 定义：

```text
src/main/java/io/github/mikuwwl/matchingreplay/failure/ReplayFailure.java
src/main/java/io/github/mikuwwl/matchingreplay/failure/ReplayFailureCode.java
```

测试证据：

```text
src/test/java/io/github/mikuwwl/matchingreplay/aeron/ReplayFailureScenarioIntegrationTest.java
```

核心判断：

```java
if (event.eventSequence() != lastAppliedEventSequence + 1)
{
    sequenceGapsThisRun++;
    throw new ReplayException(ReplayFailure.sequenceGap(...));
}
```

## 9. 本 Lab 的最终结论

```text
eventSequence > lastAppliedEventSequence + 1
    → SEQUENCE_GAP
    → 不应用当前事件
    → 不推进有效业务状态
    → 不推进到错误事件的 Checkpoint
    → 保留最后一个有效 Checkpoint
    → 不创建 Completion Proof
```

一句话记忆：

> Duplicate 可以跳过业务效果，但 Gap 不能跳过，必须停止并保留最后一个有效位置。

下一步 Lab：做 Live Bounded Replay，先捕获 `stopPosition`，再追加事件，验证 Replay 不会越过捕获边界。
