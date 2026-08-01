# Matching Engine → Aeron Archive → Replay 最小复现工程指导

> **用途**：将本文件直接交给 Codex / GPT-5.6 Sol Extra High，按阶段完成一个可运行、可测试、可演示的 Maven 多模块 Java 项目。  
> **项目定位**：复现“撮合引擎产生事件 → Aeron 传输 → Aeron Archive 持久化 → Consumer 中断 → 按 Position Replay 恢复”这一段。  
> **明确不复现**：撮合前端、订单网关、用户鉴权、充值提现、账户清算、风控中心、完整交易所微服务。  
> **真实性边界**：这是基于公开 Aeron 机制和本人能够确认的技术组件构建的学习复现，不应声称为 OKX 内部源码或完整内部架构。
> **协议层修订**：本版本使用 SBE XML Schema 和构建期生成的 Java Codec。`Agrona DirectBuffer / UnsafeBuffer` 仅作为 SBE Codec 与 Aeron 的底层缓冲区，不再手写字段 Offset。

---

# 0. 已确认事实与设计假设

## 0.1 本人能够确认的经历要素

以下内容可以作为项目背景，但不要扩展成未经确认的 OKX 内部事实：

```text
Java
Maven
Matching Engine
Aeron
Aeron Archive
Replay
```

本人当年参与的是撮合引擎相关的 Replay 部分；撮合前段由其他团队成员负责。

## 0.2 本复现项目主动采用的设计

以下属于本项目为了形成完整可运行闭环而做出的工程设计，不代表 OKX 原实现：

```text
In-memory OrderBook
Price-Time Priority
Synthetic Order Feed
SBE XML Message Schema
SBE Generated Encoder / Decoder
Agrona DirectBuffer / UnsafeBuffer
Consumer Checkpoint File
Business Event Sequence
Replay Coordinator
Projection / Replay Verifier
```

## 0.3 最重要的诚实声明

README 必须包含：

> This repository is an independent educational reconstruction based on public Aeron APIs and the author's prior exposure to matching-engine replay work. It does not contain or claim to reproduce proprietary OKX source code or confidential architecture.

---

# 1. 最终目标

完成后，项目必须能够演示：

```text
1. Archive Node 启动 ArchivingMediaDriver。
2. Matching Engine 在内存 OrderBook 中处理模拟限价订单。
3. Matching Engine 使用 SBE 生成的 Encoder，将状态变化编码到 Agrona UnsafeBuffer。
4. 事件通过 Aeron ExclusivePublication 发布。
5. Aeron Archive 将同一 Aeron Stream 录制到磁盘。
6. Projection Consumer 实时消费事件。
7. Consumer 在第 N 条事件后被强制终止。
8. Matching Engine 继续发布剩余事件。
9. Consumer 重启后读取持久化 Checkpoint。
10. Replay Coordinator 从 Checkpoint 对应的 Aeron Position 开始重放。
11. Consumer 补齐缺失事件。
12. 最终证明：业务序号连续、无缺失、无重复业务效果。
```

最小可靠性声明：

> A consumer that crashes after durably checkpointing an Aeron stream position can recover the missing matching events from Aeron Archive and converge to the same projection state as uninterrupted consumption.

---

# 2. 技术栈

必须使用：

```text
Java 21
Apache Maven
Maven Wrapper
Aeron Transport
Aeron Archive
ArchivingMediaDriver
Aeron ExclusivePublication
Aeron Subscription
Simple Binary Encoding (SBE)
SBE Tool / Generated Codecs
SBE MessageHeader
Agrona DirectBuffer
Agrona MutableDirectBuffer
Agrona UnsafeBuffer
Agrona IdleStrategy
In-memory OrderBook
Append-only recorded stream
Replay from Aeron Position
JUnit
PowerShell scripts
```

依赖版本固定为：

```xml
<aeron.version>1.52.2</aeron.version>
<agrona.version>2.4.1</agrona.version>
<sbe.version>1.37.1</sbe.version>
```

说明：

- `Aeron Archive` 负责将 Aeron Stream 录制到持久化存储并支持 Replay。
- `SBE` 定义撮合事件的二进制协议，并生成高性能 Encoder / Decoder。
- `Agrona DirectBuffer` / `MutableDirectBuffer` 是 SBE 生成 Codec 与 Aeron 之间的底层缓冲区接口。
- `UnsafeBuffer` 作为可复用的发送缓冲区；业务代码不得手写字段 Offset。
- 使用 `aeron-all` 简化学习项目的依赖管理。
- 显式声明 Agrona，确保项目直接使用其 API，并通过 `mvn dependency:tree` 检查不存在多个冲突版本。

JVM 必须带上：

```text
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens java.base/java.util.zip=ALL-UNNAMED
```

---

# 3. 明确的非目标

MVP 中禁止引入：

```text
Spring Boot
Spring Cloud
Kafka
RabbitMQ
PostgreSQL
Redis
Docker Compose
Kubernetes
Aeron Cluster
Raft
Disruptor
JSON / Protobuf / Avro on the Aeron hot path
Netty
REST API
Web UI
```

原因：

> 本项目只证明 Matching Engine 事件经过 Aeron Archive 后能够被可靠 Replay。不要让外围框架掩盖核心机制。

MVP 必须使用 SBE XML Schema 和构建期生成的 Java Codec。不要手写字段 Offset；`DirectBuffer` / `UnsafeBuffer` 仍作为生成 Codec 的底层缓冲区。

---

# 4. 核心架构

```text
┌─────────────────────────────────────────────┐
│ SyntheticOrderFeed                         │
│ 仅用于生成确定性订单，不属于撮合前端复现     │
└──────────────────────┬──────────────────────┘
                       │ OrderCommand
                       ▼
┌─────────────────────────────────────────────┐
│ Matching Engine                             │
│                                             │
│ Single Thread                               │
│ In-memory OrderBook                         │
│ Price-Time Priority                         │
│ MatchingEventFactory                        │
└──────────────────────┬──────────────────────┘
                       │ MatchingEvent
                       ▼
┌─────────────────────────────────────────────┐
│ SBE Generated Codecs                       │
│                                             │
│ MessageHeaderEncoder / Decoder              │
│ Matching Event Encoders / Decoders          │
│ Agrona MutableDirectBuffer / UnsafeBuffer   │
└──────────────────────┬──────────────────────┘
                       │ publication.offer(...)
                       ▼
┌─────────────────────────────────────────────┐
│ Aeron ExclusivePublication                 │
│ channel = aeron:ipc                         │
│ streamId = 1001                             │
└───────────────┬─────────────────┬───────────┘
                │                 │
                │ live            │ spy / recording
                ▼                 ▼
┌───────────────────────┐  ┌────────────────────────┐
│ Projection Consumer   │  │ Aeron Archive          │
│                       │  │                        │
│ Live Subscription     │  │ Recording Segments     │
│ Header.position()     │  │ Catalog                │
│ Checkpoint Store      │  │ recordingId            │
└───────────────────────┘  └───────────┬────────────┘
                                      │ startReplay
                                      ▼
                            ┌────────────────────────┐
                            │ Replay Coordinator     │
                            │                        │
                            │ recordingId            │
                            │ startPosition          │
                            │ stopPosition           │
                            │ replay streamId = 1002 │
                            └───────────┬────────────┘
                                        ▼
                            ┌────────────────────────┐
                            │ Projection Consumer    │
                            │ Replay Subscription    │
                            │ Gap/Duplicate checks   │
                            └────────────────────────┘
```

---

# 5. Event Log 的定义

本项目中：

```text
Event Log = Aeron Archive 中持久化的 Matching Event Stream
```

不要额外实现第二套自定义 append-only log。

必须区分：

## 5.1 Aeron Transport

```text
Publication
→ Media Driver
→ Subscription
```

职责：

```text
低延迟、高吞吐的进程内或网络消息传输
```

## 5.2 Aeron Archive

```text
Aeron Stream
→ Recording
→ Archive Segment Files
→ Replay
```

职责：

```text
将 Stream 持久化
查询 Recording
从指定 Position Replay
```

## 5.3 Business Event Sequence

```text
eventSequence = 1, 2, 3, 4...
```

职责：

```text
业务连续性检查
Gap 检查
Duplicate 检查
消费幂等
可读日志
```

## 5.4 Aeron Position

```text
Aeron Position = Stream 中不断增长的字节位置
```

职责：

```text
确定 Archive Replay 的起点
```

Aeron Position 不是事件数量，因为它也受 Aeron Frame Header、Padding、Fragmentation 和 MTU 影响。

---

# 6. 为什么同时需要 eventSequence 和 Aeron Position

| 字段 | 负责什么 | 是否等于事件条数 |
|---|---|---:|
| `eventSequence` | 业务事件连续性 | 是，按本项目约定连续递增 |
| `Header.position()` | Aeron 字节流进度 | 否 |
| `recordingId` | 标识 Archive Recording | 否 |
| `sessionId` | 标识 Publication Session | 否 |
| `streamId` | 标识逻辑 Stream | 否 |

恢复时：

```text
Aeron Position
→ 告诉 Archive 从哪里读取

eventSequence
→ 告诉业务层是否缺失或重复
```

必须同时保存：

```text
lastAppliedEventSequence
lastAppliedAeronPosition
```

---

# 7. Maven 多模块结构

Codex 必须创建以下结构：

```text
matching-aeron-replay-lab/
│
├── pom.xml
├── README.md
├── AGENTS.md
├── .gitignore
├── .mvn/
├── mvnw
├── mvnw.cmd
│
├── docs/
│   ├── architecture.md
│   ├── protocol.md
│   ├── crash-matrix.md
│   ├── demo-runbook.md
│   ├── evidence.md
│   └── resume-notes.md
│
├── scripts/
│   ├── clean-data.ps1
│   ├── build.ps1
│   ├── run-archive.ps1
│   ├── run-engine.ps1
│   ├── run-consumer-crash.ps1
│   ├── run-replay.ps1
│   ├── run-demo.ps1
│   └── inspect-archive.ps1
│
├── runtime/
│   ├── aeron/
│   ├── archive/
│   ├── checkpoints/
│   ├── manifests/
│   └── logs/
│
├── replay-domain/
│   └── src/
│       ├── main/java/...
│       └── test/java/...
│
├── replay-codec/
│   ├── src/main/resources/sbe/
│   │   ├── matching-events.xml
│   │   └── sbe.xsd
│   ├── src/main/java/...            # thin adapters / template dispatch
│   ├── target/generated-sources/sbe # generated, never hand-edited
│   └── src/test/java/...
│
├── orderbook-engine/
│   └── src/
│       ├── main/java/...
│       └── test/java/...
│
├── aeron-infrastructure/
│   └── src/
│       ├── main/java/...
│       └── test/java/...
│
├── matching-engine-app/
│   └── src/main/java/...
│
├── projection-consumer-app/
│   └── src/main/java/...
│
├── replay-coordinator-app/
│   └── src/main/java/...
│
└── replay-integration-tests/
    └── src/test/java/...
```

模块职责：

| Module | 职责 |
|---|---|
| `replay-domain` | Order、OrderCommand、MatchingEvent、EventType 等纯领域类型 |
| `replay-codec` | 保存 SBE XML Schema，在 Maven `generate-sources` 阶段生成 Java Codec，并封装编码/分发逻辑 |
| `orderbook-engine` | 内存 OrderBook 和撮合算法 |
| `aeron-infrastructure` | Aeron 配置、Archive Node、Publisher、Replay Client、Checkpoint |
| `matching-engine-app` | 生成订单、调用 OrderBook、发布事件 |
| `projection-consumer-app` | 实时消费、模拟崩溃、保存 Checkpoint |
| `replay-coordinator-app` | 从 Archive 指定 Position 进行 Replay |
| `replay-integration-tests` | 完整崩溃恢复测试 |

---

# 8. 根 Maven POM 要求

根 `pom.xml` 必须：

```xml
<packaging>pom</packaging>
```

包含所有 modules。

关键 properties：

```xml
<properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

    <aeron.version>1.52.2</aeron.version>
    <agrona.version>2.4.1</agrona.version>
    <sbe.version>1.37.1</sbe.version>
    <junit.version>5.13.4</junit.version>

    <maven.compiler.plugin.version>3.14.1</maven.compiler.plugin.version>
    <maven.surefire.plugin.version>3.5.4</maven.surefire.plugin.version>
    <maven.failsafe.plugin.version>3.5.4</maven.failsafe.plugin.version>
    <exec.maven.plugin.version>3.5.1</exec.maven.plugin.version>
    <build.helper.plugin.version>3.6.1</build.helper.plugin.version>
</properties>
```

Dependency management：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.aeron</groupId>
            <artifactId>aeron-all</artifactId>
            <version>${aeron.version}</version>
        </dependency>

        <dependency>
            <groupId>org.agrona</groupId>
            <artifactId>agrona</artifactId>
            <version>${agrona.version}</version>
        </dependency>

        <dependency>
            <groupId>org.junit</groupId>
            <artifactId>junit-bom</artifactId>
            <version>${junit.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Plugin management 必须包含：

```text
maven-compiler-plugin
maven-surefire-plugin
maven-failsafe-plugin
exec-maven-plugin
maven-enforcer-plugin
```

Surefire/Failsafe 添加：

```xml
<argLine>
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
    --add-opens java.base/java.util.zip=ALL-UNNAMED
</argLine>
```

Maven Enforcer：

```text
Java >= 21
Maven >= 3.9
禁止重复依赖版本
```

执行：

```powershell
.\mvnw.cmd dependency:tree
```

确认：

```text
只有一个 org.agrona:agrona 版本
所有 io.aeron 组件版本一致
```

---

# 9. 领域模型

Package 建议：

```text
io.github.mikuwwl.matchingreplay.domain
```

## 9.1 Side

```java
public enum Side
{
    BUY,
    SELL
}
```

## 9.2 OrderStatus

```java
public enum OrderStatus
{
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED
}
```

## 9.3 EventType

```java
public enum EventType
{
    ORDER_ACCEPTED(1),
    TRADE_EXECUTED(2),
    ORDER_PARTIALLY_FILLED(3),
    ORDER_FILLED(4);

    private final int code;

    EventType(final int code)
    {
        this.code = code;
    }

    public int code()
    {
        return code;
    }

    public static EventType fromCode(final int code)
    {
        // Fail fast on unknown values.
    }
}
```

## 9.4 LimitOrderCommand

```java
public record LimitOrderCommand(
    long orderId,
    int symbolId,
    Side side,
    long price,
    long quantity,
    long receivedTimestampNs)
{
}
```

金额和数量必须使用整数：

```text
price    = 最小价格单位，例如 cents / ticks
quantity = 最小数量单位
```

禁止使用：

```text
double
float
BigDecimal in hot path
```

## 9.5 Order

```java
public final class Order
{
    private final long orderId;
    private final int symbolId;
    private final Side side;
    private final long price;
    private final long originalQuantity;
    private final long prioritySequence;

    private long remainingQuantity;
    private OrderStatus status;

    // Constructor, getters and controlled state transition methods.
}
```

## 9.6 MatchingEvent

```java
public record MatchingEvent(
    short schemaVersion,
    EventType eventType,
    long eventSequence,
    long timestampNs,
    long orderId,
    long contraOrderId,
    long tradeId,
    int symbolId,
    Side side,
    long price,
    long quantity,
    long remainingQuantity)
{
}
```

---

# 10. OrderBook 最小实现

## 10.1 数据结构

```java
private final NavigableMap<Long, ArrayDeque<Order>> bids =
    new TreeMap<>(Comparator.reverseOrder());

private final NavigableMap<Long, ArrayDeque<Order>> asks =
    new TreeMap<>();
```

含义：

```text
Bids：最高价格优先
Asks：最低价格优先
同一价格：队列头部先成交，保证时间优先
```

## 10.2 限价单匹配规则

BUY 可成交条件：

```text
bestAsk <= buyLimitPrice
```

SELL 可成交条件：

```text
bestBid >= sellLimitPrice
```

成交价格：

```text
使用 Resting Order 的价格
```

成交数量：

```text
min(incoming.remainingQuantity, resting.remainingQuantity)
```

## 10.3 单线程约束

MVP 中 OrderBook 只能由一个 Engine Thread 修改。

禁止：

```text
synchronized OrderBook
ConcurrentHashMap as orderbook
多个线程同时 match
parallelStream
```

原因：

```text
单线程事件顺序
确定性
减少锁
更接近高性能撮合核心的设计思路
```

## 10.4 事件产生顺序

每个新订单：

```text
ORDER_ACCEPTED
→ 0..N 个 TRADE_EXECUTED
→ ORDER_PARTIALLY_FILLED 或 ORDER_FILLED
```

所有事件共享一个全局递增：

```java
private long nextEventSequence = 1;
```

所有 Trade 使用全局递增：

```java
private long nextTradeId = 1;
```

## 10.5 OrderBook 测试

必须覆盖：

```text
最高 Bid 优先
最低 Ask 优先
同价时间优先
部分成交
完全成交
一个主动单吃掉多个挂单
不交叉价格不成交
事件序号严格连续
TradeId 严格连续
金额和数量不溢出
```

---

# 11. Synthetic Order Feed

因为不复现撮合前端，创建：

```text
SyntheticOrderFeed
```

职责：

```text
生成确定性的 LimitOrderCommand
直接调用 MatchingEngine.submit(command)
```

必须支持：

```text
--orderCount
--seed
--symbolId
--publishDelayMicros
```

默认：

```text
orderCount = 5_000
seed = 20210801
symbolId = 1
```

订单生成必须确保：

```text
既有挂单
也有交叉订单
产生足够多的 Trade Event
```

不要使用真正随机不可复现的数据。

相同 seed 必须产生相同：

```text
订单
成交
事件数量
最终 OrderBook hash
```

---

# 12. SBE 协议与 Agrona Buffer

## 12.1 正确的分层

本项目必须采用：

```text
业务事件模型
→ SBE XML Schema
→ Maven generate-sources
→ SBE Generated Encoder / Decoder
→ Agrona MutableDirectBuffer / DirectBuffer
→ Aeron Publication / Subscription
```

不要把下面两个概念混为一谈：

```text
SBE
→ 二进制消息协议与生成式 Codec

Agrona DirectBuffer / UnsafeBuffer
→ Codec 和 Aeron 操作的底层字节缓冲区
```

Aeron 本身不强制使用 SBE，但本项目为了贴近低延迟金融系统实践，必须使用 SBE，不允许手写固定 Offset 协议。

## 12.2 replay-codec 模块目录

```text
replay-codec/
├── pom.xml
├── src/main/resources/sbe/
│   ├── matching-events.xml
│   └── sbe.xsd
├── src/main/java/com/example/replay/codec/
│   ├── MatchingEventSbeEncoder.java
│   ├── MatchingEventSbeDispatcher.java
│   └── UnknownTemplateException.java
├── src/test/java/...
└── target/generated-sources/sbe/
    └── com/example/replay/codec/generated/...
```

约束：

```text
target/generated-sources/sbe
→ 由 Maven 生成
→ 不提交手工修改
→ 不允许业务代码复制生成类后再维护
```

## 12.3 SBE Schema

创建 `src/main/resources/sbe/matching-events.xml`。

至少定义：

```text
MessageHeader
Side enum
OrderAccepted template
OrderMatched template
TradeCreated template
```

建议 Schema：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<sbe:messageSchema
    xmlns:sbe="http://fixprotocol.io/2016/sbe"
    package="com.example.replay.codec.generated"
    id="100"
    version="1"
    semanticVersion="1.0.0"
    byteOrder="littleEndian">

    <types>
        <composite name="messageHeader">
            <type name="blockLength" primitiveType="uint16"/>
            <type name="templateId" primitiveType="uint16"/>
            <type name="schemaId" primitiveType="uint16"/>
            <type name="version" primitiveType="uint16"/>
        </composite>

        <enum name="Side" encodingType="uint8">
            <validValue name="BUY">1</validValue>
            <validValue name="SELL">2</validValue>
        </enum>
    </types>

    <sbe:message name="OrderAccepted" id="1">
        <field name="eventSequence" id="1" type="int64"/>
        <field name="timestampNs" id="2" type="int64"/>
        <field name="orderId" id="3" type="int64"/>
        <field name="symbolId" id="4" type="int32"/>
        <field name="side" id="5" type="Side"/>
        <field name="price" id="6" type="int64"/>
        <field name="quantity" id="7" type="int64"/>
        <field name="remainingQuantity" id="8" type="int64"/>
    </sbe:message>

    <sbe:message name="OrderMatched" id="2">
        <field name="eventSequence" id="1" type="int64"/>
        <field name="timestampNs" id="2" type="int64"/>
        <field name="makerOrderId" id="3" type="int64"/>
        <field name="takerOrderId" id="4" type="int64"/>
        <field name="symbolId" id="5" type="int32"/>
        <field name="price" id="6" type="int64"/>
        <field name="quantity" id="7" type="int64"/>
        <field name="makerRemainingQuantity" id="8" type="int64"/>
        <field name="takerRemainingQuantity" id="9" type="int64"/>
    </sbe:message>

    <sbe:message name="TradeCreated" id="3">
        <field name="eventSequence" id="1" type="int64"/>
        <field name="timestampNs" id="2" type="int64"/>
        <field name="tradeId" id="3" type="int64"/>
        <field name="makerOrderId" id="4" type="int64"/>
        <field name="takerOrderId" id="5" type="int64"/>
        <field name="symbolId" id="6" type="int32"/>
        <field name="price" id="7" type="int64"/>
        <field name="quantity" id="8" type="int64"/>
    </sbe:message>
</sbe:messageSchema>
```

注意：

- 金额、价格和数量使用缩放后的整数，不使用 `double`。
- `templateId` 区分三种事件，Consumer 不依赖自定义 `eventType` 字节。
- Schema ID、Template ID 和字段 ID 一旦发布不得随意重用。
- Schema 兼容性改动通过 `version` / `sinceVersion` 管理。

## 12.4 Maven 生成 Codec

`replay-codec/pom.xml` 必须在 `generate-sources` 阶段运行 SBE Tool，并将输出目录加入源码路径。

参考配置：

```xml
<properties>
    <sbe.generated.dir>${project.build.directory}/generated-sources/sbe</sbe.generated.dir>
</properties>

<build>
    <plugins>
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>exec-maven-plugin</artifactId>
            <version>${exec.maven.plugin.version}</version>
            <dependencies>
                <dependency>
                    <groupId>uk.co.real-logic</groupId>
                    <artifactId>sbe-tool</artifactId>
                    <version>${sbe.version}</version>
                </dependency>
            </dependencies>
            <executions>
                <execution>
                    <id>generate-sbe-codecs</id>
                    <phase>generate-sources</phase>
                    <goals>
                        <goal>java</goal>
                    </goals>
                    <configuration>
                        <mainClass>uk.co.real_logic.sbe.SbeTool</mainClass>
                        <includePluginDependencies>true</includePluginDependencies>
                        <includeProjectDependencies>false</includeProjectDependencies>
                        <arguments>
                            <argument>${project.basedir}/src/main/resources/sbe/matching-events.xml</argument>
                        </arguments>
                        <systemProperties>
                            <systemProperty>
                                <key>sbe.output.dir</key>
                                <value>${sbe.generated.dir}</value>
                            </systemProperty>
                            <systemProperty>
                                <key>sbe.target.language</key>
                                <value>Java</value>
                            </systemProperty>
                            <systemProperty>
                                <key>sbe.validation.stop.on.error</key>
                                <value>true</value>
                            </systemProperty>
                            <systemProperty>
                                <key>sbe.validation.warnings.fatal</key>
                                <value>true</value>
                            </systemProperty>
                            <systemProperty>
                                <key>sbe.validation.xsd</key>
                                <value>${project.basedir}/src/main/resources/sbe/sbe.xsd</value>
                            </systemProperty>
                        </systemProperties>
                    </configuration>
                </execution>
            </executions>
        </plugin>

        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>build-helper-maven-plugin</artifactId>
            <version>${build.helper.plugin.version}</version>
            <executions>
                <execution>
                    <id>add-sbe-generated-sources</id>
                    <phase>generate-sources</phase>
                    <goals>
                        <goal>add-source</goal>
                    </goals>
                    <configuration>
                        <sources>
                            <source>${sbe.generated.dir}</source>
                        </sources>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

如果该配置在所选 Maven / Plugin 版本中存在类路径问题，Codex 可以改用等价的 `maven-antrun-plugin` 或在 `generate-sources` 阶段执行 `sbe-all`，但必须保持：

```text
Schema 是唯一协议来源
Codec 在构建期生成
生成源码加入编译路径
clean verify 可从空 target 目录成功生成
```

## 12.5 编码

发送侧仍然复用 Agrona `UnsafeBuffer`：

```java
private final UnsafeBuffer sendBuffer =
    new UnsafeBuffer(ByteBuffer.allocateDirect(512));

private final MessageHeaderEncoder headerEncoder =
    new MessageHeaderEncoder();

private final TradeCreatedEncoder tradeEncoder =
    new TradeCreatedEncoder();
```

概念代码：

```java
tradeEncoder.wrapAndApplyHeader(sendBuffer, 0, headerEncoder)
    .eventSequence(event.eventSequence())
    .timestampNs(event.timestampNs())
    .tradeId(event.tradeId())
    .makerOrderId(event.makerOrderId())
    .takerOrderId(event.takerOrderId())
    .symbolId(event.symbolId())
    .price(event.price())
    .quantity(event.quantity());

final int encodedLength =
    MessageHeaderEncoder.ENCODED_LENGTH + tradeEncoder.encodedLength();

publisher.offer(sendBuffer, 0, encodedLength);
```

这里仍然出现 `UnsafeBuffer`，但字段布局由 SBE 生成代码负责，不由业务代码手写 `putLong(offset, ...)`。

## 12.6 解码与 Template Dispatch

接收侧直接使用 Aeron 回调提供的 `DirectBuffer`：

```java
void onFragment(
    final DirectBuffer buffer,
    final int offset,
    final int length,
    final Header header)
{
    messageHeaderDecoder.wrap(buffer, offset);

    final int templateId = messageHeaderDecoder.templateId();
    final int actingBlockLength = messageHeaderDecoder.blockLength();
    final int actingVersion = messageHeaderDecoder.version();
    final int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;

    switch (templateId)
    {
        case OrderAcceptedDecoder.TEMPLATE_ID ->
            decodeOrderAccepted(buffer, bodyOffset, actingBlockLength, actingVersion, header.position());

        case OrderMatchedDecoder.TEMPLATE_ID ->
            decodeOrderMatched(buffer, bodyOffset, actingBlockLength, actingVersion, header.position());

        case TradeCreatedDecoder.TEMPLATE_ID ->
            decodeTradeCreated(buffer, bodyOffset, actingBlockLength, actingVersion, header.position());

        default -> throw new UnknownTemplateException(templateId);
    }
}
```

生成的 Decoder 应直接包装 inbound `DirectBuffer`，不要先复制到 `byte[]`。

## 12.7 Codec 测试

必须覆盖：

```text
SBE generation 在 clean build 中执行
三种 Template round-trip
MessageHeader templateId dispatch
schemaId 校验
actingVersion / actingBlockLength 传递正确
未知 templateId fail fast
价格和数量使用缩放整数
最大 long 边界
生成 Decoder 直接读取 DirectBuffer
编码长度不超过 Aeron max payload
```

再增加 Schema 稳定性测试或构建检查：

```text
禁止重复 templateId
禁止重复 field id
禁止删除并重用已发布字段 ID
生成源码不得被手工修改
```

---

# 13. Aeron 常量

创建：

```java
public final class AeronChannels
{
    public static final String LIVE_CHANNEL = "aeron:ipc";
    public static final int LIVE_STREAM_ID = 1001;

    public static final String REPLAY_CHANNEL = "aeron:ipc";
    public static final int REPLAY_STREAM_ID = 1002;

    public static final String AERON_DIR_PROPERTY = "replay.aeron.dir";
    public static final String ARCHIVE_DIR_PROPERTY = "replay.archive.dir";

    private AeronChannels()
    {
    }
}
```

说明：

- MVP 使用 `aeron:ipc`，便于 Windows 单机多进程演示。
- 所有进程必须连接到同一个 Aeron Directory。
- Archive Node 持有 Media Driver；其他应用只启动 Aeron Client。
- 不允许每个模块各自启动一个独立 Media Driver。

---

# 14. Archive Node

类：

```text
io.github.mikuwwl.matchingreplay.aeron.ArchiveNodeMain
```

核心组件：

```text
MediaDriver.Context
Archive.Context
ArchivingMediaDriver
```

伪代码：

```java
public static void main(final String[] args)
{
    final Path aeronDir = RuntimePaths.aeronDir();
    final Path archiveDir = RuntimePaths.archiveDir();

    final MediaDriver.Context mediaDriverContext =
        new MediaDriver.Context()
            .aeronDirectoryName(aeronDir.toString())
            .dirDeleteOnStart(false)
            .dirDeleteOnShutdown(false);

    final Archive.Context archiveContext =
        new Archive.Context()
            .archiveDir(archiveDir.toFile())
            .deleteArchiveOnStart(false);

    try (ArchivingMediaDriver driver =
             ArchivingMediaDriver.launch(
                 mediaDriverContext,
                 archiveContext))
    {
        System.out.println("ARCHIVE_READY");
        ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();
        barrier.await();
    }
}
```

要求：

```text
启动时打印 Aeron Directory
启动时打印 Archive Directory
注册错误处理器
注册 Shutdown Hook
不得静默删除 Archive 数据
```

PowerShell 的 `clean-data.ps1` 才负责主动清理。

---

# 15. 启动 Recording

Matching Engine 发布前必须：

```text
连接 Aeron
连接 AeronArchive Client
startRecording(LIVE_CHANNEL, LIVE_STREAM_ID, SourceLocation.LOCAL)
创建 ExclusivePublication
等待 Publication connected
```

建议类：

```text
ArchiveRecordingManager
```

伪代码：

```java
archive.startRecording(
    AeronChannels.LIVE_CHANNEL,
    AeronChannels.LIVE_STREAM_ID,
    SourceLocation.LOCAL);

try (ExclusivePublication publication =
         aeron.addExclusivePublication(
             AeronChannels.LIVE_CHANNEL,
             AeronChannels.LIVE_STREAM_ID))
{
    awaitConnected(publication);
    // Find recording counter for publication session.
}
```

重要：

> `startRecording` 是异步操作。Codex 必须确认 Recording 已与 Publication Session 关联后，再进入正式发布阶段。

获取 Recording：

```text
publication.sessionId()
→ RecordingPos.findCounterIdBySession(...)
→ RecordingPos.getRecordingId(...)
```

如果当前 Aeron 1.52.2 API 签名与示例略有变化：

```text
以实际依赖编译结果和官方 Javadoc 为准调整
不得通过删除 Recording 校验来绕过
```

将以下内容写入：

```text
runtime/manifests/current-run.properties
```

示例：

```properties
runId=2026-08-02T09-00-00Z
recordingId=7
publicationSessionId=123456
liveStreamId=1001
channel=aeron:ipc
firstEventSequence=1
lastEventSequence=0
lastPublishedPosition=0
```

---

# 16. Aeron Matching Event Publisher

类：

```text
AeronMatchingEventPublisher
```

必须使用：

```text
ExclusivePublication
UnsafeBuffer
MatchingEventEncoder
IdleStrategy
```

接口：

```java
public interface MatchingEventPublisher
{
    long publish(MatchingEvent event);
}
```

返回值：

```text
Publication position after successful offer
```

核心逻辑：

```java
long result;

while ((result = publication.offer(buffer, 0, encodedLength)) < 0)
{
    if (result == Publication.BACK_PRESSURED)
    {
        backPressureCount++;
        idleStrategy.idle();
        continue;
    }

    if (result == Publication.ADMIN_ACTION)
    {
        idleStrategy.idle();
        continue;
    }

    if (result == Publication.NOT_CONNECTED)
    {
        idleStrategy.idle();
        continue;
    }

    if (result == Publication.CLOSED)
    {
        throw new IllegalStateException("Publication is closed");
    }

    if (result == Publication.MAX_POSITION_EXCEEDED)
    {
        throw new IllegalStateException("Publication max position exceeded");
    }

    throw new IllegalStateException("Unknown offer result: " + result);
}

idleStrategy.reset();
return result;
```

要求：

```text
不得忽略 offer 的负返回值
不得把失败 offer 当成发布成功
不得无限 Busy Spin 且无指标
必须统计 backPressureCount
必须允许超时后 fail fast
```

---

# 17. Archive Durability 边界

必须在 README 明确：

```text
publication.offer() > 0
```

表示：

```text
消息被 Aeron Publication 接受，并获得新的 Stream Position
```

它不自动证明：

```text
Archive 已经将该 Position 持久化完成
```

MVP 为了形成确定性演示，在 Publisher 完成一批事件后执行：

```text
publication.position()
→ await archive recording position >= publication position
```

创建：

```java
public void awaitRecorded(
    long recordingId,
    long requiredPosition,
    Duration timeout)
```

逻辑：

```text
循环查询 getRecordingPosition(recordingId)
或 Recording Position Counter
直到 position >= requiredPosition
```

超时必须报错。

MVP 不要求每条事件都同步等待 Archive，因为那会将演示变成每条消息的同步磁盘确认，并显著改变延迟特征。

必须在文档区分：

```text
Transport accepted
Archive recorded
Consumer applied
Consumer checkpointed
```

这是四个不同阶段。

---

# 18. Matching Engine App

入口：

```text
MatchingEngineMain
```

命令参数：

```text
--orderCount=5000
--seed=20210801
--symbolId=1
--publishDelayMicros=0
```

流程：

```text
1. 连接同一个 Media Driver。
2. 连接 Aeron Archive Client。
3. 开始 Recording。
4. 创建 ExclusivePublication。
5. 等待 Publication 和 Recording Ready。
6. 启动单线程 Matching Engine。
7. SyntheticOrderFeed 生成订单。
8. OrderBook 每产生一个 MatchingEvent，立即编码并发布。
9. 更新 RunManifest。
10. 所有订单结束后等待 Archive 追上最终 Publication Position。
11. 打印最终摘要。
```

最终日志格式：

```text
ENGINE_FINISHED
ordersSubmitted=5000
eventsPublished=10432
tradesCreated=2716
firstSequence=1
lastSequence=10432
publicationPosition=834560
recordingId=7
orderBookHash=...
backPressureCount=...
```

---

# 19. Projection Consumer

入口：

```text
ProjectionConsumerMain
```

参数：

```text
--mode=live
--crashAfterSequence=4000
--checkpointEvery=1
--consumerName=asset-projection
```

虽然名字叫 Asset Projection，本项目不实现真实账户清算，只实现：

```text
事件应用
连续性检查
状态 hash
Checkpoint
重复抑制
```

## 19.1 Projection State

```java
public final class ProjectionState
{
    private long lastAppliedEventSequence;
    private long lastAppliedAeronPosition;
    private long appliedEventCount;
    private long duplicateEventCount;
    private long gapCount;
    private long stateHash;
}
```

## 19.2 事件应用规则

收到事件：

```text
eventSequence == lastSequence + 1
→ 正常应用
```

```text
eventSequence <= lastSequence
→ Duplicate
→ 不产生第二次业务效果
→ duplicateEventCount++
```

```text
eventSequence > lastSequence + 1
→ Gap
→ 立即失败
→ 不允许继续假装成功
```

## 19.3 State Hash

为了证明 uninterrupted 和 replay 后状态一致：

```java
stateHash = mix(
    stateHash,
    event.eventSequence(),
    event.eventType().code(),
    event.orderId(),
    event.tradeId(),
    event.price(),
    event.quantity());
```

必须使用确定性算法。

可使用简单 FNV-1a 64-bit 或明确实现的混合函数，不得使用 JVM 对象默认 `hashCode()`。

---

# 20. Checkpoint Store

Checkpoint 文件：

```text
runtime/checkpoints/asset-projection.checkpoint
```

内容至少包含：

```properties
consumerName=asset-projection
recordingId=7
lastAppliedEventSequence=4000
lastAppliedAeronPosition=320000
appliedEventCount=4000
duplicateEventCount=0
gapCount=0
stateHash=...
updatedAt=...
```

## 20.1 原子持久化

必须：

```text
写入 .tmp
fsync / force
atomic move replace
```

Java 伪代码：

```java
Path temp = checkpoint.resolveSibling(
    checkpoint.getFileName() + ".tmp");

try (FileChannel channel = FileChannel.open(
    temp,
    CREATE,
    TRUNCATE_EXISTING,
    WRITE))
{
    channel.write(buffer);
    channel.force(true);
}

Files.move(
    temp,
    checkpoint,
    ATOMIC_MOVE,
    REPLACE_EXISTING);
```

如果文件系统不支持 `ATOMIC_MOVE`：

```text
记录明确警告
回退到 REPLACE_EXISTING
```

## 20.2 保存哪个 Position

在 Fragment Handler 中：

```java
onFragment(
    DirectBuffer buffer,
    int offset,
    int length,
    Header header)
```

事件成功应用后，保存：

```text
header.position()
```

这里表示该 Fragment 处理后的 Position。

恢复时从这个 Position 开始 Replay，目标是获取下一条消息。

## 20.3 业务状态与 Checkpoint 原子性

MVP 中 Projection State 的所有字段与 Position 写入同一个 Checkpoint 文件，因此：

```text
状态摘要
lastEventSequence
lastAeronPosition
```

一起替换。

不要分成三个独立文件。

真实资产系统通常需要数据库事务、Write-Ahead Log、Inbox 或嵌入式状态存储保证原子性；本项目只用单一原子快照文件演示该思想。

---

# 21. FragmentAssembler

Consumer 必须使用：

```java
FragmentAssembler
```

即使当前事件只有 80 bytes，也不要把“小消息不会 Fragment”写死。

结构：

```java
FragmentHandler handler = this::onCompleteMessage;
FragmentAssembler assembler = new FragmentAssembler(handler);

while (running)
{
    int fragments = subscription.poll(assembler, 10);
    idleStrategy.idle(fragments);
}
```

这样协议增大后仍能正确处理 Aeron Fragmentation。

---

# 22. 模拟硬崩溃

当：

```text
eventSequence == crashAfterSequence
```

处理顺序必须是：

```text
1. 应用事件
2. 原子写入 Checkpoint
3. 打印 SIMULATED_CRASH
4. Runtime.getRuntime().halt(77)
```

使用 `halt` 而不是普通异常：

```text
不执行 finally
不执行正常 close
更接近进程突然死亡
```

日志：

```text
SIMULATED_CRASH
consumer=asset-projection
lastSequence=4000
checkpointPosition=320000
exitCode=77
```

---

# 23. Replay Coordinator

入口：

```text
ReplayCoordinatorMain
```

参数：

```text
--consumerName=asset-projection
--followLive=false
```

MVP 默认只做 bounded replay，不做 Live Replay Merge。

## 23.1 恢复流程

```text
1. 读取 Checkpoint。
2. 读取 RunManifest。
3. 验证 recordingId 一致。
4. 查询 Archive Recording startPosition。
5. 查询 active recordingPosition 或 stopped stopPosition。
6. 验证 checkpointPosition 在合法区间内。
7. 计算 replayLength = replayStopPosition - checkpointPosition。
8. startReplay(recordingId, checkpointPosition, replayLength, replayChannel, replayStreamId)。
9. 使用返回的 replaySessionId 创建带 session-id 的 Subscription。
10. Poll Replay Stream。
11. 应用事件并持续更新 Checkpoint。
12. 到达 replayStopPosition 后结束。
13. 对照 RunManifest 验证 final eventSequence。
```

## 23.2 选择 Stop Position

如果 Recording 仍 active：

```text
getRecordingPosition(recordingId)
```

如果 Recording 已停止：

```text
getStopPosition(recordingId)
```

必须把本次 Replay 的目标 Position 固定下来：

```text
replayStopPositionSnapshot
```

否则 Publisher 持续写入时，测试边界会不断变化。

## 23.3 Replay API 形态

使用：

```java
long replaySessionId = archive.startReplay(
    recordingId,
    checkpointPosition,
    replayLength,
    AeronChannels.REPLAY_CHANNEL,
    AeronChannels.REPLAY_STREAM_ID);
```

然后：

```java
String replaySessionChannel =
    ChannelUri.addSessionId(
        AeronChannels.REPLAY_CHANNEL,
        (int) replaySessionId);

Subscription subscription =
    aeron.addSubscription(
        replaySessionChannel,
        AeronChannels.REPLAY_STREAM_ID);
```

若 1.52.2 的 API 提供 ReplayParams，也可以使用，但必须保持：

```text
指定 recordingId
指定 startPosition
指定 bounded length
绑定 replay session
```

不得简单从 0 开始重放后靠业务层忽略所有旧消息来冒充 Position Replay。

---

# 24. Recording 选择规则

不要在存在多个 Recording 时无脑选择：

```text
latest recording
```

MVP 应优先：

```text
RunManifest 中的 recordingId
```

作为回放目标。

如果 Manifest 缺失，允许提供诊断工具：

```text
listRecordingsForUri(channel, streamId)
```

但自动恢复必须 fail fast：

```text
无法唯一确定 recordingId
→ 停止
→ 打印候选 recordings
```

---

# 25. Replay 完成条件

Replay 结束时必须同时满足：

```text
lastAppliedAeronPosition >= replayStopPosition
lastAppliedEventSequence == manifest.lastEventSequence
gapCount == 0
stateHash == manifest.expectedProjectionHash
```

关于 `duplicateEventCount`：

- 正常 checkpoint 后 Replay 应为 0。
- 专门的 duplicate 测试可以故意从更早 Position 开始，验证业务序号去重。
- 主演示要求：

```text
duplicateEventCount == 0
```

输出：

```text
REPLAY_COMPLETED
recordingId=7
replayStartPosition=320000
replayStopPosition=834560
firstRecoveredSequence=4001
lastRecoveredSequence=10432
finalSequence=10432
gaps=0
duplicates=0
stateHash=...
status=PASS
```

---

# 26. Run Manifest

Matching Engine 在完成后写：

```text
runtime/manifests/current-run.properties
```

至少包括：

```properties
runId=...
recordingId=...
publicationSessionId=...
channel=aeron:ipc
liveStreamId=1001
firstEventSequence=1
lastEventSequence=10432
eventsPublished=10432
lastPublishedPosition=834560
recordedPosition=834560
expectedProjectionHash=...
orderBookHash=...
seed=20210801
orderCount=5000
```

Manifest 同样使用：

```text
tmp + force + atomic move
```

---

# 27. End-to-End Demo 流程

PowerShell `run-demo.ps1` 必须自动完成：

```text
1. clean-data
2. build
3. start Archive Node
4. wait until ARCHIVE_READY
5. start Projection Consumer with crashAfterSequence=4000
6. start Matching Engine
7. wait until Consumer exits with code 77
8. wait until Matching Engine finishes
9. start Replay Coordinator
10. verify REPLAY_COMPLETED status=PASS
11. stop Archive Node
12. print evidence paths
```

推荐顺序也可以先启动 Consumer，再启动 Engine，确保 Live Subscription ready。

## 27.1 进程日志

```text
runtime/logs/archive.log
runtime/logs/consumer-live.log
runtime/logs/engine.log
runtime/logs/replay.log
```

## 27.2 PowerShell 进程启动

使用：

```powershell
Start-Process `
  -FilePath ".\mvnw.cmd" `
  -ArgumentList "..." `
  -RedirectStandardOutput "runtime/logs/archive.log" `
  -RedirectStandardError "runtime/logs/archive.err.log" `
  -PassThru
```

脚本必须：

```text
保存 PID
等待 Ready 标记
设置超时
失败时打印对应日志尾部
finally 中清理子进程
```

---

# 28. Maven 运行命令

Archive：

```powershell
.\mvnw.cmd -pl aeron-infrastructure -am exec:java `
  -Dexec.mainClass=io.github.mikuwwl.matchingreplay.aeron.ArchiveNodeMain
```

Consumer：

```powershell
.\mvnw.cmd -pl projection-consumer-app -am exec:java `
  -Dexec.mainClass=io.github.mikuwwl.matchingreplay.consumer.ProjectionConsumerMain `
  -Dexec.args="--mode=live --crashAfterSequence=4000"
```

Engine：

```powershell
.\mvnw.cmd -pl matching-engine-app -am exec:java `
  -Dexec.mainClass=io.github.mikuwwl.matchingreplay.engine.MatchingEngineMain `
  -Dexec.args="--orderCount=5000 --seed=20210801"
```

Replay：

```powershell
.\mvnw.cmd -pl replay-coordinator-app -am exec:java `
  -Dexec.mainClass=io.github.mikuwwl.matchingreplay.replay.ReplayCoordinatorMain `
  -Dexec.args="--consumerName=asset-projection"
```

所有命令都必须附加所需 JVM `--add-opens`。可以通过：

```text
MAVEN_OPTS
exec-maven-plugin jvmArgs
scripts 中的环境变量
```

统一配置。

---

# 29. 测试要求

## 29.1 Unit Tests

### Domain

```text
Order rejects invalid quantity
Order rejects invalid price
Order state transitions are legal
```

### OrderBook

```text
price priority
time priority
partial fill
full fill
multi-level match
deterministic sequence
```

### Codec

```text
round-trip
invalid magic
invalid version
short message
unknown enum
```

### Checkpoint

```text
write/read round-trip
atomic replacement
corrupt checkpoint fails
missing checkpoint returns empty state
```

### Projection

```text
continuous event applies
duplicate event suppressed
gap event fails
stateHash deterministic
```

## 29.2 Integration Tests

### Test 1: Archive record and full replay

```text
Publish 1,000 events
Wait until Archive catches publication position
Replay from startPosition
Assert sequences 1..1000
```

### Test 2: Replay from checkpoint Position

```text
Consume 400 events
Save header.position()
Publish until 1,000
Replay from saved Position
Assert first replayed business sequence == 401
Assert last == 1000
```

### Test 3: Simulated duplicate

```text
Replay from Position before last checkpoint
Assert duplicate sequence is suppressed
Assert stateHash unchanged by duplicate
```

### Test 4: Invalid Position

```text
checkpointPosition < recordingStartPosition
or
checkpointPosition > recordingStopPosition
→ Fail fast
```

### Test 5: Crash demo

尽量通过 Failsafe 或外部进程测试验证：

```text
live consumer exits 77
replay finishes
final hash matches uninterrupted reference
```

---

# 30. Uninterrupted Reference Run

为了证明 Replay 正确，Integration Test 必须生成一个 Reference：

```text
同一组 MatchingEvent
→ 不崩溃地全部应用
→ referenceStateHash
```

然后崩溃恢复流程：

```text
部分 Live Apply
→ Crash
→ Replay remaining
→ recoveredStateHash
```

断言：

```text
referenceStateHash == recoveredStateHash
referenceLastSequence == recoveredLastSequence
gapCount == 0
```

这是项目最有价值的证明。

---

# 31. Crash Matrix

创建 `docs/crash-matrix.md`：

| Crash Point | Archive | Consumer Checkpoint | 恢复动作 | 预期 |
|---|---|---|---|---|
| Publication 前 | 无新事件 | 旧位置 | 无需恢复该事件 | 上游需重放命令，本 MVP 不覆盖 |
| offer 成功、Archive 未追上 | 可能未持久化 | 旧位置 | 不可宣称 durable | MVP 结束前等待 Archive |
| Archive 已记录、Consumer 未收到 | 有 | 旧位置 | Replay | 恢复 |
| Consumer 收到、应用前 | 有 | 旧位置 | Replay | 再处理一次 |
| Consumer 应用并 checkpoint 后 | 有 | 新位置 | 从新位置 Replay | 从下一条继续 |
| Consumer 应用后、checkpoint 前 | 有 | 旧位置 | 可能重复 | eventSequence 去重 |
| Replay 中途崩溃 | 有 | 最近 checkpoint | 再次 Replay | 继续恢复 |
| Archive Node 崩溃 | 磁盘有已录制数据 | 保持 | 重启 Archive 后 Replay | 已落盘部分可恢复 |

必须强调：

> 本 MVP 不解决“Matching Engine 内存 OrderBook 已变化，但事件尚未被 Aeron Publication 接受”这一窗口。那需要 Command Log、Aeron Cluster、同步 Journal 或更完整的撮合状态机持久化设计，属于后续阶段。

---

# 32. 指标与可观察性

不引入 Micrometer。

使用轻量 counters 和结构化日志：

```text
eventsPublished
publicationBackPressureCount
archiveRecordedPosition
archiveLagBytes
liveEventsConsumed
replayEventsConsumed
duplicateEventsSuppressed
gapDetected
checkpointWriteCount
checkpointWriteLatencyNs
replayDurationMs
```

关键计算：

```text
archiveLagBytes =
publication.position() - recordingPosition
```

```text
consumerLagBytes =
recordingPosition - lastAppliedAeronPosition
```

日志必须包含：

```text
runId
recordingId
sessionId
streamId
eventSequence
aeronPosition
```

---

# 33. Archive 检查工具

`scripts/inspect-archive.ps1` 使用 Aeron Archive Tool 或项目内诊断类输出：

```text
Archive directory
Catalog recordings
recordingId
streamId
sessionId
startPosition
stopPosition
active recordingPosition
channel
```

如果直接运行官方 ArchiveTool，需要 JVM `--add-opens`。

也可以实现：

```text
ListRecordingsMain
```

使用：

```text
AeronArchive.listRecordingsForUri(...)
```

---

# 34. README 必须讲清楚的知识点

README 至少包含：

## 34.1 Aeron 与 Aeron Archive

```text
Aeron = Transport
Aeron Archive = Durable recording and replay
```

## 34.2 Event Log

本项目把：

```text
Aeron Archive Recording
```

作为：

```text
Matching Event Log
```

## 34.3 Replay

Replay 不是重新撮合。

它是：

```text
读取已经发生的 Matching Event
→ 重新构建下游 Projection
```

## 34.4 OrderBook 与 Projection

```text
OrderBook = 撮合核心内存状态
Projection = 下游根据事件构建的派生状态
```

本 MVP Replay 的是：

```text
Matching Events
```

恢复的是：

```text
Projection
```

不承诺恢复 Matching Engine 自身 OrderBook。

## 34.5 Position

必须解释：

```text
业务 eventSequence
和
Aeron byte position
```

的区别。

## 34.6 At-least-once

Replay 可能造成重复投递，因此：

```text
Consumer 必须幂等
```

本项目使用：

```text
eventSequence <= lastAppliedSequence
→ suppress duplicate
```

---

# 35. AGENTS.md

创建以下 Agent 规则：

```markdown
# Agent Rules

1. Do not replace Aeron or Aeron Archive with another broker.
2. Use Maven, not Gradle.
3. Use Java 21.
4. Use an in-memory price-time-priority OrderBook.
5. Define the protocol in SBE XML and use generated SBE codecs; DirectBuffer/UnsafeBuffer are only the underlying buffers.
6. Do not use JSON on the Aeron hot path.
7. Use ExclusivePublication.
8. Persist and replay by Aeron Position.
9. Also maintain a business eventSequence for gap and duplicate checks.
10. Do not claim this is proprietary OKX source code.
11. Do not add Spring Boot, Kafka, databases, Docker, or Aeron Cluster to the MVP.
12. Run tests after every phase.
13. Keep the repository compiling at every commit.
14. Never ignore negative Publication.offer results.
15. Never select an ambiguous recording silently.
16. The final demo must prove crash recovery with matching final state hash.
```

---

# 36. Codex 执行阶段

## Phase 0：建立仓库

完成：

```text
Maven parent
modules
Maven Wrapper
.gitignore
AGENTS.md
README skeleton
runtime directories
```

验收：

```powershell
.\mvnw.cmd clean verify
```

## Phase 1：Domain 与 Codec

完成：

```text
Side
OrderStatus
EventType
LimitOrderCommand
MatchingEvent
SBE XML Schema
Generated Encoder / Decoder
Template dispatch adapter
Codec tests
```

验收：

```text
所有 codec 测试通过
无 JSON
Maven clean build 自动生成 SBE Codec
生成 Codec 底层直接包装 Agrona DirectBuffer
```

## Phase 2：OrderBook

完成：

```text
Order
OrderBook
MatchingEngine
Price-Time Priority
Matching events
Deterministic SyntheticOrderFeed
```

验收：

```text
OrderBook tests 全通过
相同 seed 产生相同事件和 hash
```

## Phase 3：Aeron Archive Node

完成：

```text
RuntimePaths
ArchiveNodeMain
ArchivingMediaDriver
错误处理
PowerShell startup
```

验收：

```text
ARCHIVE_READY
Archive 目录产生 catalog/segment 相关文件
```

## Phase 4：Matching Event Publication

完成：

```text
Recording start
ExclusivePublication
SBE generated encoder + UnsafeBuffer
offer result handling
recordingId discovery
RunManifest
awaitRecorded
```

验收：

```text
Engine 发布事件
Archive position 追上 publication position
```

## Phase 5：Live Consumer 与 Checkpoint

完成：

```text
Subscription
FragmentAssembler
ProjectionState
CheckpointStore
Gap/Duplicate logic
hard crash
```

验收：

```text
Consumer 第 4000 条后 exit 77
Checkpoint sequence == 4000
Checkpoint position > 0
```

## Phase 6：Replay Coordinator

完成：

```text
read checkpoint
read manifest
validate positions
startReplay
bind replay session
apply remaining events
verify final hash
```

验收：

```text
第一条恢复事件是 4001
最终 sequence 等于 manifest
status=PASS
```

## Phase 7：Integration Tests

完成：

```text
full replay
checkpoint replay
duplicate replay
invalid position
uninterrupted vs recovered hash
```

验收：

```powershell
.\mvnw.cmd clean verify
```

全部通过。

## Phase 8：Demo Scripts 与证据

完成：

```text
run-demo.ps1
logs
evidence.md
crash matrix
architecture diagram
```

验收：

```powershell
.\scripts\run-demo.ps1
```

一条命令完成完整演示。

---

# 37. Codex 不得偷懒的地方

禁止以下伪实现：

```text
把事件同时写普通文本文件，然后假装是 Archive Replay
Replay 时直接重新生成订单
Replay 时重新调用 OrderBook 撮合
不保存 Aeron Position，只保存 eventSequence
从 0 Replay 后把前 4000 条丢掉，冒充 Position Replay
忽略 Publication.offer 失败
用 Thread.sleep 猜 Archive 已写完
只检查消息数量，不检查顺序和状态 hash
所有模块放一个 Main 类
测试中 mock Aeron Archive，完全不启动真实 Archive
```

至少一个 Integration Test 必须启动真实：

```text
ArchivingMediaDriver
AeronArchive
ExclusivePublication
Subscription
startReplay
```

---

# 38. 常见错误与修复

## 38.1 每个进程启动自己的 Media Driver

症状：

```text
Publication 和 Subscription 永远不连接
Archive 看不到 Stream
```

修复：

```text
只有 Archive Node 启动 Media Driver
所有 Client 使用相同 aeronDirectoryName
```

## 38.2 Recording 启动晚于 Publication

症状：

```text
开头事件没有进入 Recording
```

修复：

```text
先 startRecording
确认 Recording Counter
再正式发布
```

## 38.3 把 eventSequence 当成 Replay Position

症状：

```text
startReplay(recordingId, 4000, ...)
```

错误原因：

```text
4000 是第 4000 个业务事件
不等于 Aeron byte position
```

修复：

```text
保存 Header.position()
```

## 38.4 保存 Fragment 开始位置

恢复后可能重复最后一条。

修复：

```text
事件应用成功后保存 header.position()
```

## 38.5 Checkpoint 先写 Position，再应用业务状态

可能出现：

```text
Position 已前进
业务状态没更新
→ 永久跳过事件
```

修复：

```text
业务状态摘要和 Position 一起原子持久化
```

## 38.6 直接选择 latest Recording

多个测试运行后可能选错。

修复：

```text
Manifest 持久化 recordingId
```

## 38.7 Engine 退出前不等 Archive

可能：

```text
Publication position > Archive recording position
```

修复：

```text
awaitRecorded(finalPosition)
```

---

# 39. 最终验收清单

## Build

- [ ] Maven Wrapper 可用
- [ ] `clean verify` 通过
- [ ] Java 21
- [ ] Aeron/Agrona 版本单一

## OrderBook

- [ ] 内存 OrderBook
- [ ] Price-Time Priority
- [ ] 单线程修改
- [ ] 确定性输入
- [ ] 确定性事件序列

## Aeron

- [ ] ExclusivePublication
- [ ] Subscription
- [ ] `aeron:ipc`
- [ ] 共享 Media Driver
- [ ] 正确处理 offer 返回值
- [ ] SBE XML Schema 和生成 Codec
- [ ] 生成 Encoder 包装 MutableDirectBuffer / UnsafeBuffer
- [ ] 生成 Decoder 包装 DirectBuffer
- [ ] 业务代码没有手写字段 Offset

## Archive

- [ ] ArchivingMediaDriver
- [ ] Recording 在 Publication 前启动
- [ ] 获得 recordingId
- [ ] Recording Position 可观察
- [ ] 磁盘 Archive 数据可检查
- [ ] 按 Position startReplay

## Consumer

- [ ] FragmentAssembler
- [ ] 保存 `Header.position()`
- [ ] 保存 `eventSequence`
- [ ] Gap fail fast
- [ ] Duplicate suppress
- [ ] Checkpoint 原子替换
- [ ] 强制崩溃 exit 77

## Replay

- [ ] 从 checkpointPosition 开始
- [ ] 不从 0 假装恢复
- [ ] 第一条恢复事件正确
- [ ] 最终序号正确
- [ ] 无 Gap
- [ ] 状态 Hash 等于 uninterrupted run

## Documentation

- [ ] 不声称是 OKX 内部源码
- [ ] 解释 Event Log
- [ ] 解释 Aeron Position
- [ ] 解释 Replay
- [ ] 解释可靠性边界
- [ ] Crash Matrix
- [ ] Demo Runbook
- [ ] Evidence

---

# 40. 完成后可用于面试的架构表达

英文：

> I reconstructed the replay portion of a matching-engine event pipeline using Java, Maven, Aeron, Aeron Archive, SBE-generated codecs, and Agrona DirectBuffer. The in-memory order book emits deterministic matching events through an Aeron ExclusivePublication, while Aeron Archive records the stream to durable storage. A consumer persists both the business event sequence and the Aeron stream position. After a simulated hard crash, the replay coordinator resumes from the checkpointed archive position, suppresses duplicates by business sequence, detects gaps, and proves convergence by comparing the recovered projection hash with an uninterrupted reference run.

中文：

> 我使用 Java 和 Maven 复现了撮合引擎事件流的 Replay 部分。内存 OrderBook 按价格优先、时间优先执行撮合，并通过 SBE 生成的 Codec 将事件编码到 Agrona UnsafeBuffer，再由 Aeron ExclusivePublication 发布；Aeron Archive 将 Stream 持久化。Consumer 同时保存业务事件序号与 Aeron Position，进程硬崩溃后由 Replay Coordinator 从 Checkpoint Position 继续重放，通过业务序号防重和查漏，并比较最终状态 Hash，证明恢复结果与不中断执行一致。

注意：

> 在简历中应把该项目写成“基于既往经历启发的独立复现”，而不是把新项目中的所有设计倒推成当年 OKX 的内部实现。

---

# 41. Future Work（MVP 完成后再做）

不要在 MVP 阶段实现，只记录：

```text
Aeron ReplayMerge
Live replay then merge into current stream
Snapshot + replay
OrderBook recovery
Embedded KV checkpoint store
Aeron Cluster
Replicated Archive
UDP multi-process deployment
Multi-symbol partitioning
CPU affinity
Busy-spin dedicated agents
Latency histogram
Chaos testing
```

最值得下一阶段实现的是：

```text
Aeron Archive ReplayMerge
```

它可以让 Consumer：

```text
先追历史 Recording
→ 追上 Live Position
→ 无缝切换到实时 Stream
```

---

# 42. 官方资料

Codex 实现时优先查阅以下官方资料，不要依赖随机博客：

1. Aeron Archive Overview  
   https://aeron.io/docs/aeron-archive/overview/

2. Aeron Archive Basic Sample  
   https://aeron.io/docs/aeron-archive/basic-sample/

3. Working with Recordings  
   https://aeron.io/docs/aeron-archive/working-with-recordings/

4. Understanding Aeron Position  
   https://aeron.io/docs/aeron/aeron-understanding-position/

5. Aeron Archive Replication Sample（包含从保存 Position 恢复的例子）  
   https://aeron.io/docs/aeron-archive/replication-sample/

6. Simple Binary Encoding Overview  
   https://aeron.io/docs/simple-binary-encoding/overview/

7. SBE Basic Sample  
   https://aeron.io/docs/simple-binary-encoding/basic-sample/

8. Agrona DirectBuffer  
   https://aeron.io/docs/agrona/direct-buffer/

9. SBE GitHub  
   https://github.com/aeron-io/simple-binary-encoding

10. Aeron GitHub  
   https://github.com/aeron-io/aeron

---

# 43. 直接交给 Codex 的最终指令

```text
Implement this specification as a complete Maven multi-module Java 21 repository.

Work phase by phase and keep the repository compiling after every phase.

Do not replace Aeron, Aeron Archive, SBE-generated codecs, Agrona DirectBuffer, the in-memory OrderBook,
or Maven with alternatives. Do not replace SBE with handwritten field-offset codecs.

The MVP must launch a real ArchivingMediaDriver, record a real Aeron stream,
hard-crash a consumer after a durable checkpoint, and perform a real
Aeron Archive replay from the saved Aeron Position.

The final proof must compare an uninterrupted projection state hash with the
crash-and-replay projection state hash.

Do not claim the result is proprietary OKX code or an exact reproduction of
OKX internals.

Run:
    .\mvnw.cmd clean verify
and:
    .\scripts\run-demo.ps1

Fix all compilation, test, process orchestration, and Windows path issues until
both commands pass.

Write the exact commands, outputs, recordingId, positions, event counts,
gap counts, duplicate counts, and final state hashes into docs/evidence.md.
```
