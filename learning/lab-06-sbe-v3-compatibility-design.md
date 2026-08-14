# Lab 06：SBE v3 兼容性设计

本 Lab 不直接修改生产协议或 Java 代码，而是先设计一次 Schema Evolution：在 v3 增加 recoverySource 字段。

## 1. 当前协议基线

当前 Schema：

    schemaId      = 100
    schemaVersion = 2
    semantic      = 2.0.0

当前 sourceId 是一个可选 int32，使用 sinceVersion="2"，并且追加在三个 Message Template 的固定字段末尾。

当前生成的 Block Length：

| Template | v1 最小长度 | v2 长度 |
|---|---:|---:|
| OrderAccepted | 53 | 57 |
| OrderMatched | 54 | 58 |
| TradeCreated | 69 | 73 |

## 2. v3 Schema 设计

### Schema 头

    version="3"
    semanticVersion="3.0.0"

schemaId 保持 100，因为这是同一个事件协议的向后兼容扩展。

### 新增类型

    <type
        name="RecoverySource"
        primitiveType="int32"
        presence="optional"
        nullValue="0"/>

### 新增字段

recoverySource 追加在每个 Template 的最后，不能插入旧字段中间：

    OrderAccepted:
        sourceId       id="9"  sinceVersion="2"
        recoverySource id="10" sinceVersion="3"

    OrderMatched:
        sourceId       id="10" sinceVersion="2"
        recoverySource id="11" sinceVersion="3"

    TradeCreated:
        sourceId       id="11" sinceVersion="2"
        recoverySource id="12" sinceVersion="3"

字段 ID 只要求在各自 Template 内唯一。追加字段可以保持所有旧字段 Offset 不变。

## 3. v3 Block Length

recoverySource 是 int32，所以每个 Template 的 v3 Block Length 预计比 v2 增加 4：

| Template | v1 最小长度 | v2 长度 | v3 预测长度 |
|---|---:|---:|---:|
| OrderAccepted | 53 | 57 | 61 |
| OrderMatched | 54 | 58 | 62 |
| TradeCreated | 69 | 73 | 77 |

最终必须以 SBE Tool 生成的常量为准：

    OrderAcceptedDecoder.BLOCK_LENGTH
    OrderMatchedDecoder.BLOCK_LENGTH
    TradeCreatedDecoder.BLOCK_LENGTH

不能手写 Offset，也不能假设所有 Template 的 Block Length 相同。

## 4. v1/v2 默认值

v3 Decoder 根据 actingVersion 判断字段是否存在：

| 输入消息 | sourceId | recoverySource |
|---|---:|---:|
| v1 | 0 | 0 |
| v2 | 编码值或 0 | 0 |
| v3 | 编码值或 0 | 编码值或 0 |
| v4 | 不接受 | 不接受 |

因此，Domain 对象读取 v1/v2 时必须把 recoverySource 映射为 0。旧消息不能因为没有该字段而失败。

## 5. 后续实现清单

本 Lab 不执行以下改动，只先列出设计。

### Domain

给 MatchingEvent 增加：

    int recoverySource

保留旧构造函数，并通过重载构造函数把旧调用方的默认值设为 0。

### Encoder

    v1：不编码 sourceId 和 recoverySource
    v2：编码 sourceId，不编码 recoverySource
    v3：编码 sourceId 和 recoverySource

### Dispatcher

将支持版本从 1..2 扩展为 1..3：

    actingVersion = 1 → v1 最小长度
    actingVersion = 2 → v2 最小长度
    actingVersion = 3 → v3 Block Length

读取 v1/v2 时将 recoverySource 设为 0，读取 v3 时使用生成 Decoder 的 recoverySource()。

## 6. Digest 策略

本设计选择：

    recoverySource 不加入 Replay Digest

它表示恢复或来源元数据，不是撮合业务结果。当前 Digest 已经排除：

    timestamp
    schemaVersion
    sourceId
    Aeron transport metadata

因此 v3 的 Canonical Digest 仍然是：

    eventSequence, eventType, orderId, contraOrderId, tradeId,
    symbolId, side, price, quantity, remainingQuantity

同一业务事件仅改变 recoverySource 时，Digest 必须保持一致。如果未来把它定义为业务语义的一部分，应另起协议决策，不能偷偷改变历史 Digest。

## 7. Future Version 策略

当前 Dispatcher 对未知版本返回：

    UNSUPPORTED_SCHEMA

v3 继续采用明确拒绝：

    1 ≤ actingVersion ≤ 3 → 接受
    actingVersion < 1      → UNSUPPORTED_SCHEMA
    actingVersion > 3      → UNSUPPORTED_SCHEMA

不能因为旧字段“看起来还能读”就静默接受 v4。未来版本可能改变字段语义、枚举或校验规则。

## 8. 必须新增的测试

### v1 兼容

    编码 v1 OrderAccepted
    使用 v3 Dispatcher 解码
    recoverySource == 0
    业务字段不变

### v2 兼容

    编码 v2，sourceId=77
    使用 v3 Dispatcher 解码
    sourceId == 77
    recoverySource == 0

### v3 Round Trip

    三个 Template 都编码 recoverySource=88
    解码后 recoverySource == 88
    Header.version == 3
    Header.blockLength == 生成的 v3 BLOCK_LENGTH

### v3 Block Length 校验

    actingVersion = 3
    actingBlockLength = v3 BLOCK_LENGTH - 1
    → SBE_DECODE_FAILED

Failure 还应携带 actingVersion、actingBlockLength 和 minimumSupportedBlockLength。

### Future Version

把 Header 的 version 改为 4：

    → UNSUPPORTED_SCHEMA

### Digest 不变性

构造两个业务字段相同、仅 recoverySource 不同的 v3 事件：

    Digest(recoverySource=1) == Digest(recoverySource=2)

### 旧 Recording Replay

使用历史 v1/v2 Recording Replay：

    最终 Sequence 不变
    最终 Digest 不变
    Checkpoint 正常推进

## 9. Schema Evolution 规则

    schemaId 不变
    schema version 递增
    新字段只追加到末尾
    新字段设置 sinceVersion
    旧字段 Offset 不改变
    旧消息读取新字段默认值
    未来版本明确拒绝

不要：

    修改已有字段类型
    复用已发布 field id
    把 recoverySource 插入旧字段中间
    把来源元数据加入现有 Digest
    静默接受未知 Future Version

## 10. 设计结论

    Schema version：2 → 3
    schemaId：保持 100
    recoverySource：optional int32，nullValue=0
    sinceVersion：3
    位置：每个 Template 的固定字段末尾
    Block Length：各 Template 的 v2 长度 + 4
    v1/v2 默认值：0
    Digest：不包含 recoverySource
    Future Version：UNSUPPORTED_SCHEMA

这是一份兼容性设计，不是已经实施的 v3 协议变更。当前仓库生产 Schema 仍然是 v2。

## 11. 基线验证

在没有修改 v3 协议代码的前提下，运行当前 Codec 测试：

    .\mvnw.cmd -ntp -Dtest=MatchingEventSbeCodecTest test

结果：

    Tests run: 10, Failures: 0, Errors: 0
    BUILD SUCCESS

下一步可以把本设计实施为真实 v3 Schema；当前学习路线的基础练习到此结束。
