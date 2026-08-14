# Lab 07：PostgreSQL Projection 的事务一致性设计

本实验讨论一个架构变化：

```text
当前：Aeron Event → ProjectionState → 文件 Checkpoint
假设：Aeron Event → PostgreSQL Business Effect + Inbox + Checkpoint
```

本 Lab 只做设计和故障推演，不修改当前项目生产代码，也不要求本地启动 PostgreSQL。

## 1. 实验目标

完成本实验后，应该能回答：

1. DB Commit 成功、Checkpoint 失败怎么办？
2. Checkpoint 成功、DB Commit 失败怎么办？
3. Inbox 表应该保存什么？
4. Business Effect、Inbox 和恢复 Offset 如何处于同一个事务？
5. Duplicate 如何避免重复业务副作用？
6. 为什么这个问题和 Outbox 是不同方向的一致性问题？

## 2. 当前项目的边界

当前项目的可靠性边界是：

```text
SBE Decode
    ↓
Sequence / Duplicate / Gap 校验
    ↓
ProjectionState.apply()
    ↓
CheckpointRepository.save()
```

Checkpoint 记录 Replay 的恢复位置，例如：

```text
checkpointKey
recordingId
lastAppliedEventSequence
lastAppliedAeronPosition
replayDigest
```

文件 Checkpoint 可以保证 Replay 自己从哪里继续，但它不能自动和外部数据库的业务写入形成同一个事务。因此，下面的 PostgreSQL 方案是架构设计练习，不代表当前仓库已经实现 PostgreSQL Projection。

## 3. 为什么需要 Inbox

假设事件 Sequence 为 501，Replay 需要同时完成：

```text
1. 更新订单、成交或账户等业务表
2. 记录这个事件已经处理过
3. 保存新的 Replay Position
```

如果业务表成功、Checkpoint 没有更新，进程重启后会从旧 Position 再次读取 Sequence 501。没有 Inbox 时，业务副作用可能重复。

Inbox 是消费方的幂等记录，表示这个事件是否已经被当前业务 Projection 接受并产生副作用。它不是 RabbitMQ Outbox，也不是发送事件用的消息表。

## 4. 推荐的事务单元

一次事件处理可以设计为一个 PostgreSQL 事务：

```text
BEGIN
  1. 根据事件身份检查 Inbox
  2. 如果已经处理，执行 Duplicate 分支
  3. 如果未处理，更新业务表
  4. 插入 Inbox 记录
  5. 更新 Replay Checkpoint
COMMIT
```

关键要求：

```text
业务副作用、Inbox 记录、Checkpoint 更新必须一起提交或一起回滚
```

这样，数据库提交成功后三者都可见；数据库回滚后三者都不可见。

## 5. Inbox 的事件身份

Inbox 的唯一身份不能只使用业务 Sequence。不同 Recording 可能使用相同 Sequence，因此至少应包含：

```text
checkpointKey
recordingId
eventSequence
```

建议约束：

```text
UNIQUE (checkpoint_key, recording_id, event_sequence)
```

如果同一身份再次收到完全相同的事件，它是 Duplicate；如果身份相同但事件内容不同，不能静默忽略，应该返回数据冲突。

因此 Inbox 最好额外保存：

```text
event_hash
event_type
source_position
received_at
```

事件 Hash 用来区分：

```text
相同事件重放           → 合法 Duplicate
相同 Sequence 不同内容 → 数据冲突
```

## 6. 失败场景推演

### 场景 A：业务表、Inbox、Checkpoint 一起提交

```text
业务表写入成功
Inbox 写入成功
Checkpoint 写入成功
COMMIT 成功
```

结果是业务副作用保留、事件被标记为已处理、Replay 从新 Position 继续。这是期望路径。

### 场景 B：业务写入后进程崩溃，但事务尚未提交

```text
业务表 UPDATE 已执行
进程崩溃
事务回滚
```

业务表、Inbox 和 Checkpoint 都没有新提交。恢复后再次处理该事件是安全的。

### 场景 C：事务提交成功后进程崩溃

```text
业务表、Inbox、Checkpoint 一起 COMMIT
进程随后崩溃
```

恢复时 Checkpoint 已经前进；即使从稍旧 Position 重读，Inbox 也会阻止重复业务副作用。

### 场景 D：先提交业务表，再写文件 Checkpoint

```text
DB COMMIT 成功
进程在写文件 Checkpoint 前崩溃
```

恢复时文件 Checkpoint 仍然是旧 Position。没有 Inbox 时，Sequence 501 会再次产生业务副作用。

### 场景 E：先写文件 Checkpoint，再提交 DB

```text
文件 Checkpoint 已前进
DB COMMIT 失败
```

恢复时系统可能跳过数据库没有成功应用的事件，形成业务数据丢失。因此不能让 Checkpoint 先于业务提交。

## 7. Checkpoint 的两种设计

### 设计 A：Checkpoint 也放在 PostgreSQL

```text
业务表
Inbox
Replay Checkpoint
```

三者位于同一个数据库事务中，原子性最直接。示意字段：

```text
replay_checkpoint(
  checkpoint_key,
  recording_id,
  last_event_sequence,
  last_aeron_position,
  replay_digest,
  updated_at
)
```

优点是业务状态和恢复位置一起提交。代价是每个 Replay 事务都依赖 PostgreSQL，数据库延迟会影响 Replay 吞吐，还需要处理连接池背压。

### 设计 B：Checkpoint 仍然是文件

如果业务库和 Checkpoint 文件不能进入同一个事务，就必须把 Inbox 作为数据库中的最终幂等边界：

```text
数据库 Inbox 决定业务事件是否已经成功应用
文件 Checkpoint 主要用于减少重放范围
```

恢复流程必须允许从稍旧的文件 Position 重新读取事件：已提交事件走 Duplicate 分支，未提交事件重新执行业务事务。此时文件 Position 不是业务提交的唯一证明，Inbox 才是数据库副作用的幂等依据。

## 8. Duplicate 处理算法

```text
BEGIN

读取 Inbox(checkpointKey, recordingId, eventSequence)

不存在：
  校验 Sequence 和事件内容
  执行业务副作用
  INSERT Inbox
  UPDATE Checkpoint

存在且 event_hash 相同：
  不重复执行业务副作用
  必要时推进 transport Position

存在但 event_hash 不同：
  ROLLBACK
  报告 DATA_CONFLICT

COMMIT
```

数据库唯一约束是最后一道并发保护。应用层的“先查询再插入”不能替代唯一约束，因为两个 Worker 可能同时查询到“没有记录”。

## 9. 并发处理

同一个 `checkpointKey` 不应该被两个 Replay Job 同时写入：

```text
Replay A：Sequence 501 → 502
Replay B：Sequence 501 → 503
```

并发写同一 Projection 可能导致 Checkpoint 倒退、Digest 顺序不一致或业务表出现非确定结果。

当前项目的 Job Manager 使用 active checkpoint key 做单实例保护。多实例部署时，需要把保护机制放到共享系统，例如数据库 advisory lock、数据库 lease 表或分布式锁服务。

锁只解决“谁可以运行”，不替代 Inbox 的幂等约束，也不替代数据库事务。

## 10. Inbox 与 Outbox 的区别

```text
Inbox：我收到的事件是否已经处理？
Outbox：我需要发送出去的事件是否已经可靠落库？
```

典型方向：

```text
上游事件 → Inbox → 本地业务副作用
本地业务副作用 → Outbox → 下游消息
```

本 Lab 关注 Replay Consumer 的 Inbox，不是 RabbitMQ Publisher 的 Outbox。两者可以同时存在，但解决的是相反方向的问题。

## 11. 与 Aeron Position 的关系

```text
Position：Replay 从哪里继续读
Sequence：业务事件处理到了哪里
Inbox：某个业务事件是否已经产生副作用
Digest：业务结果是否保持一致
```

它们不能互相替代：只保存 Position 无法判断业务副作用是否提交；只保存 Sequence 无法准确恢复 Aeron Fragment 边界；只保存 Digest 不能定位失败位置。

## 12. 设计验收清单

- [ ] 业务表、Inbox 和 Checkpoint 是否在同一个事务中？
- [ ] Inbox 是否有 `(checkpointKey, recordingId, eventSequence)` 唯一约束？
- [ ] 是否保存事件 Hash 来识别同身份不同内容？
- [ ] DB Commit 成功后，重启是否安全？
- [ ] DB Commit 失败后，Checkpoint 是否不会错误前进？
- [ ] 是否区分 Inbox 和 Outbox？
- [ ] 是否说明多实例如何保护同一个 `checkpointKey`？
- [ ] 是否区分 Aeron Position 和业务 Sequence？
- [ ] 是否明确当前仓库没有实现 PostgreSQL 代码？

## 13. 五个问题的答案要点

### DB Commit 成功、Checkpoint 失败怎么办？

如果 Inbox 和业务效果已经提交，恢复时允许从旧 Position 重新读取；Inbox 将事件识别为 Duplicate，不能再次产生业务副作用。更理想的方案是把 Checkpoint 也放入同一个数据库事务。

### Checkpoint 成功、DB Commit 失败怎么办？

不能允许这种提交顺序。否则恢复会跳过数据库没有成功应用的事件，造成业务数据缺失。

### Inbox 表放在哪里？

放在产生业务副作用的同一个 PostgreSQL 数据库中，使 Inbox、业务表和数据库 Checkpoint 可以使用同一个事务。

### Business Effect 和 Offset 如何处于同一事务？

使用同一个数据库连接和事务边界，在 `COMMIT` 前完成业务写入、Inbox 插入和 Checkpoint 更新；任一步骤失败都回滚。

### Duplicate 如何避免重复副作用？

使用数据库唯一约束加事件 Hash。相同身份且 Hash 相同走幂等 Duplicate 分支；相同身份但 Hash 不同报告数据冲突，不能静默跳过。

## 14. 本 Lab 的边界结论

本设计没有改变当前 Replay 服务：

```text
没有新增 PostgreSQL Driver
没有新增 SQL Migration
没有新增 Repository
没有改变当前文件 Checkpoint 行为
```

迁移到数据库 Projection 时最重要的结论是：

```text
业务副作用成功 ≠ Replay Position 已安全提交
Transport Position ≠ 业务幂等记录
Inbox ≠ Outbox
```
