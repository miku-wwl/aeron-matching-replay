# Lab 01：跟踪一次正常 Replay

本 Lab 用 10 条事件，观察业务 Sequence、Aeron Position、Replay Digest、应用计数和 Checkpoint 如何一起变化。

## 1. 学习目标

完成本 Lab 后，应能回答：

1. `eventSequence` 和 Aeron `Position` 有什么区别？
2. Digest 为什么每处理一条事件都会变化？
3. 为什么内存中的 Position 会领先于磁盘 Checkpoint？
4. 如果处理到第 9 条时进程崩溃，恢复起点大概在哪里？

核心关系：

```text
业务 Sequence       业务事件顺序
Aeron Position      Archive Replay 的传输位置
Replay Digest       对已应用事件结果的确定性摘要
Checkpoint          崩溃恢复进度
```

## 2. 运行 Lab

在项目根目录执行：

```powershell
.\mvnw.cmd -ntp -Dtest=ReplayTraceExerciseTest test
```

成功标准：

```text
Tests run: 1, Failures: 0, Errors: 0
BUILD SUCCESS
```

本 Lab 对应的测试文件：

```text
src/test/java/io/github/mikuwwl/matchingreplay/experiments/exercise1/ReplayTraceExerciseTest.java
```

注意：这是一个学习用测试。它使用真实的嵌入式 Aeron Archive 生成事件位置，但为了方便观察，直接把事件应用到 `ProjectionState`，不是完整的生产 Replay Coordinator 流程。

## 3. 实际输出

```text
Replay Exercise 1
recordingId=0
checkpointEvery=5
Sequence | Aeron Position | Digest Before | Digest After | Applied Count | Checkpoint Position
1 | 128 | 14695981039346656037 | 6648147102466089700 | 1 | -
2 | 256 | 6648147102466089700 | 17134817281326324209 | 2 | -
3 | 384 | 17134817281326324209 | 12675450391669006306 | 3 | -
4 | 512 | 12675450391669006306 | 6005072350625735757 | 4 | -
5 | 640 | 6005072350625735757 | 5907549510849062968 | 5 | 640
6 | 768 | 5907549510849062968 | 3478624117437311737 | 6 | -
7 | 896 | 3478624117437311737 | 17475520246467697534 | 7 | -
8 | 1024 | 17475520246467697534 | 8401973210925744229 | 8 | -
9 | 1152 | 8401973210925744229 | 3415593354024250396 | 9 | -
10 | 1280 | 3415593354024250396 | 14083126683612928849 | 10 | 1280
```

## 4. 逐列理解

### 4.1 Sequence

```text
1, 2, 3, ..., 10
```

这是业务事件顺序，由事件中的 `eventSequence` 表示。

它用于判断：

```text
下一个事件是否正确
是否出现 Duplicate
是否出现 Sequence Gap
```

### 4.2 Aeron Position

```text
128, 256, 384, ..., 1280
```

这是 Aeron Archive 中的传输位置，不是业务编号。

本次测试中每条事件增加 128，是因为学习 Fixture 使用了固定长度的测试事件。真实系统中，编码长度、Frame 对齐和消息内容可能不同，不能写成：

```text
Aeron Position = Sequence × 128
```

正确理解是：

```text
Sequence 负责业务顺序
Position 负责从 Archive 恢复 Replay
```

### 4.3 Digest Before / Digest After

Digest 是对已应用事件进行滚动计算的 64 位摘要。

每一行满足：

```text
当前行 Digest Before
    = 上一行 Digest After
```

例如：

```text
Sequence 1 Digest After = 6648147102466089700
Sequence 2 Digest Before = 6648147102466089700
```

它用于验证两次 Replay 是否处理出了相同结果：

```text
正常 Replay Digest
    ==
Crash Recovery Replay Digest
```

输出使用无符号格式显示，因此部分值会大于 Java `long` 的有符号最大值。

Digest 不是完整 OrderBook、数据库或账户状态的替代品。它只是本项目定义的确定性事件流摘要。

### 4.4 Applied Count

本次事件是连续的：

```text
1, 2, 3, ..., 10
```

没有 Duplicate，也没有 Gap，所以每成功应用一条事件，计数增加 1：

```text
Applied Count = 1, 2, 3, ..., 10
```

### 4.5 Checkpoint Position

测试设置：

```java
CHECKPOINT_EVERY = 5
```

因此只在第 5 条和第 10 条事件之后保存 Checkpoint：

```text
Sequence 5  → Checkpoint Position 640
Sequence 10 → Checkpoint Position 1280
```

`-` 表示当前这一行没有执行新的保存，不代表 Checkpoint 不存在。

处理到 Sequence 9 时，状态可能是：

```text
内存中的 Position       = 1152
磁盘中的 Checkpoint     = 640
```

这就是 Checkpoint Cadence 带来的正常差距。

## 5. 崩溃恢复推演

假设进程处理完 Sequence 9 后突然崩溃：

```text
最后一次持久化 Checkpoint：Sequence 5 / Position 640
崩溃前内存状态：Sequence 9 / Position 1152
```

新进程只能相信已经原子保存的 Checkpoint，因此会从第 5 条事件之后继续 Replay，重新处理尚未保存进度的事件。

结果是：

```text
不会跳过 Sequence 6～9
可能重新处理部分事件
Checkpoint 不会凭空前进到 1152
```

这解释了为什么 Checkpoint 必须在事件成功解码、校验和应用之后保存。

## 6. 对应源码

学习测试：

```text
src/test/java/io/github/mikuwwl/matchingreplay/experiments/exercise1/ReplayTraceExerciseTest.java
```

核心状态处理：

```text
src/main/java/io/github/mikuwwl/matchingreplay/projection/ProjectionState.java
```

重点方法：

```java
state.apply(event, position);
checkpoints.save(state.checkpoint(...));
```

`ProjectionState.apply()` 负责：

```text
检查 Aeron Position 是否倒退
检查 Duplicate
检查 Sequence Gap
更新 Digest
更新业务 Sequence
更新 Aeron Position
增加 Applied Count
```

## 7. 本 Lab 的最终结论

```text
Sequence  = 业务事件顺序
Position  = Aeron Archive 恢复位置
Digest    = 确定性处理结果摘要
Checkpoint = 崩溃恢复进度
```

一次正常 Replay 的基本顺序是：

```text
读取事件
  → 应用事件
  → 更新 Digest
  → 更新业务 Sequence
  → 更新 Aeron Position
  → 按 cadence 保存 Checkpoint
```

下一步 Lab：插入 Duplicate，验证 Duplicate 会推进 Position，但不会改变 Digest 和 Applied Count。
