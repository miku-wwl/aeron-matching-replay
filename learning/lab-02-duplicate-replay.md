# Lab 02：Duplicate 事件如何处理

本 Lab 故意把事件序列设置为：

```text
1, 2, 3, 3, 4
```

第二个 `3` 是 Duplicate。目标是验证：

```text
Duplicate 会推进 Aeron Position
Duplicate 不会改变 Digest
Duplicate 不会增加 Applied Count
Duplicate 会增加 Duplicate Count
```

## 1. 运行 Lab

在项目根目录执行：

```powershell
.\mvnw.cmd -ntp -Dtest=DuplicateReplayExerciseTest test
```

成功标准：

```text
Tests run: 1, Failures: 0, Errors: 0
BUILD SUCCESS
```

测试文件：

```text
src/test/java/io/github/mikuwwl/matchingreplay/experiments/exercise2/DuplicateReplayExerciseTest.java
```

## 2. 实际输出

```text
Replay Exercise 2
recordedSequences=1, 2, 3, 3, 4
Index | Sequence | Aeron Position | Result | Digest Before | Digest After | Applied Count | Duplicate Count | Checkpoint Position
0 | 1 | 128 | APPLIED | 14695981039346656037 | 11144074183960764028 | 1 | 0 | -
1 | 2 | 256 | APPLIED | 11144074183960764028 | 9780432828870177910 | 2 | 0 | -
2 | 3 | 384 | APPLIED | 9780432828870177910 | 129928405189391479 | 3 | 0 | -
3 | 3 | 512 | DUPLICATE | 129928405189391479 | 129928405189391479 | 3 | 1 | -
4 | 4 | 640 | APPLIED | 129928405189391479 | 12401903217546137693 | 4 | 1 | 640
```

## 3. 逐行观察 Duplicate

关键行：

```text
3 | 3 | 384 | APPLIED   | ... | 129928405189391479 | 3 | 0 | -
3 | 3 | 512 | DUPLICATE | 129928405189391479 | 129928405189391479 | 3 | 1 | -
```

### 3.1 Sequence 相同

两个事件的业务 Sequence 都是 `3`：

```text
第一个 3：正常应用
第二个 3：已经处理过，因此是 Duplicate
```

判断代码：

```java
if (event.eventSequence() <= lastAppliedEventSequence)
```

当 `lastAppliedEventSequence` 已经是 `3` 时，再收到 Sequence `3`，就会进入 Duplicate 分支。

### 3.2 Position 仍然前进

第一个 Sequence `3` 的位置：

```text
384
```

第二个 Sequence `3` 的位置：

```text
512
```

因此：

```text
384 → 512
```

即使业务事件被判定为 Duplicate，它仍然是 Archive 中一个已经消费到的 Fragment。Replay 不能停留在旧 Position，否则恢复时会反复读到同一条消息。

### 3.3 Digest 不变

Duplicate 行的 Digest 是：

```text
Digest Before = 129928405189391479
Digest After  = 129928405189391479
```

说明 Duplicate 没有再次混入 Digest。

如果 Duplicate 再次更新 Digest，那么相同的事件流在重试或恢复后可能得到不同结果，确定性验证就失效了。

### 3.4 Applied Count 不变

Duplicate 前：

```text
Applied Count = 3
```

Duplicate 后仍然是：

```text
Applied Count = 3
```

因为它没有产生新的业务应用效果。

### 3.5 Duplicate Count 增加

Duplicate 前：

```text
Duplicate Count = 0
```

Duplicate 后：

```text
Duplicate Count = 1
```

这让系统可以在不重复执行副作用的情况下，统计上游重投递或重复事件。

## 4. 为什么不能直接丢弃 Duplicate？

“不应用”不等于“完全忽略”。正确处理是：

```text
业务层：不重复应用
传输层：继续推进 Position
统计层：增加 Duplicate Count
恢复层：保存新的 Position
```

如果只判断 Duplicate，却不更新 Position：

```text
Replay 重新开始
    ↓
再次读到同一个 Duplicate
    ↓
再次判断 Duplicate
    ↓
永远无法前进
```

## 5. 最终状态

处理完 `1, 2, 3, 3, 4` 后：

```text
lastAppliedEventSequence = 4
lastAppliedAeronPosition = 640
appliedEventsTotal       = 4
duplicatesTotal          = 1
```

最终 Digest 等于只应用一次 `1, 2, 3, 4` 的 Digest，而不是把重复的 `3` 计算两次。

测试还验证了：包含重复事件的完整 Recording Digest，与业务最终 Digest 不相等。这是预期行为，因为 Recording Fixture 会把所有原始消息混入参考 Digest，而 `ProjectionState` 的 Digest 只包含真正应用的业务事件。

## 6. Checkpoint 观察

本 Lab 在最后一条事件处理后保存 Checkpoint：

```text
Checkpoint Position = 640
```

Duplicate 本身没有触发单独 Checkpoint，但它推进的 Position 会被最终 Checkpoint 包含。

如果系统在 Duplicate 之后、Sequence 4 之前崩溃，Checkpoint cadence 决定了恢复时是否需要重新读取部分消息；即使重新读到 Duplicate，也不会重复产生业务效果。

## 7. 对应源码

状态判断和更新：

```text
src/main/java/io/github/mikuwwl/matchingreplay/projection/ProjectionState.java
```

核心逻辑：

```java
if (event.eventSequence() <= lastAppliedEventSequence)
{
    duplicatesTotal++;
    lastAppliedAeronPosition = aeronPosition;
    return ApplyResult.DUPLICATE;
}
```

测试 Fixture 为了区分两个相同业务 Sequence 的不同传输位置，增加了按 Recording 索引读取 Position 的方法：

```text
src/test/java/io/github/mikuwwl/matchingreplay/support/EmbeddedArchiveFixture.java
```

## 8. 本 Lab 的最终结论

Duplicate 的处理规则是：

```text
eventSequence <= lastAppliedEventSequence
    → Duplicate
    → 不更新 Digest
    → 不增加 Applied Count
    → 增加 Duplicate Count
    → 更新 Aeron Position
```

一句话记忆：

> Duplicate 不产生新的业务效果，但仍然是已经消费过的传输消息。

下一步 Lab：插入 Sequence Gap，例如 `1, 2, 4`，观察 Replay 为什么立即失败，并且 Checkpoint 停留在最后一个有效事件。
