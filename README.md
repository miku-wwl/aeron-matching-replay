# Aeron Archive Replay Service

这是一个单体 Spring Boot 回放服务。它只负责协调和执行 Replay，不再把撮合引擎、
事件生产方、Media Driver、Aeron Archive 和 Consumer 拆成仓库里的多个应用。

> 本项目是基于公开 Aeron API 的独立示例，不包含、也不声称复现 OKX 私有源码或机密架构。

## 生产边界

```text
┌──────────────────────── 上游系统 ────────────────────────┐
│ Matching / Event Service                                │
│   └─ SBE MatchingEvent ──> Aeron ──> Aeron Archive      │
└─────────────────────────────┬────────────────────────────┘
                              │ recordingId + Position
                              ▼
┌──────────────────── Aeron Replay Service ────────────────┐
│ POST /api/v1/replays                                     │
│   └─ ReplayJobManager（异步、同 checkpointKey 串行）      │
│       └─ AeronReplayCoordinator                          │
│           ├─ 连接外部 Media Driver / Archive             │
│           ├─ 按 recordingId + Position 有界回放           │
│           ├─ SBE 解码、eventSequence gap/duplicate 检查   │
│           └─ 原子更新 Checkpoint、校验最终状态哈希         │
└──────────────────────────────────────────────────────────┘
```

仓库现在只有一个 Maven artifact 和一个可部署进程：

```text
aeron-replay-service-1.0.0-SNAPSHOT.jar
```

用于启动 `ArchivingMediaDriver` 和测试事件 Publisher 的夹具只存在于 `src/test`，
不会进入生产 JAR，也不会由服务代码调用。

## 快速开始

要求 Java 21。构建并运行全部测试：

```powershell
.\scripts\build.ps1
```

连接已经运行的 Media Driver / Aeron Archive：

```powershell
.\scripts\run-service.ps1 `
  -AeronDirectory "D:\aeron\driver" `
  -CheckpointDirectory ".\runtime\checkpoints" `
  -Port 8080
```

启动一次回放：

```powershell
.\scripts\start-replay.ps1 `
  -RecordingId 42 `
  -CheckpointKey "orders-projection" `
  -StopPosition 1349472 `
  -ExpectedLastEventSequence 12425 `
  -ExpectedStateHash "18013645834701933210" `
  -CorrelationId "incident-20260802"
```

查询任务：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/replays/{jobId}
Invoke-RestMethod http://localhost:8080/api/v1/replays
Invoke-RestMethod http://localhost:8080/actuator/health
```

## 回放请求语义

`recordingId` 是必填项，服务绝不会自行猜测 Recording。`checkpointKey` 标识一份
独立的恢复状态；同一 key 同时只能有一个回放任务。

- 已有 Checkpoint：从 `lastAppliedAeronPosition` 继续。
- 没有 Checkpoint：从 Recording 的 `startPosition` 开始，业务序列预期从 1 开始。
- 未传 `stopPosition`：任务开始时快照当前 `recordingPosition`/`stopPosition`。
- 传入期望序列或哈希：完成后校验，不匹配时状态为 `VERIFICATION_FAILED`。
- 回放异常：状态为 `FAILED`，Checkpoint 仍停留在最后一次原子写入的位置。

Checkpoint 同时保存：

```properties
recordingId=42
lastAppliedAeronPosition=434080
lastAppliedEventSequence=4000
stateHash=...
```

业务 `eventSequence` 用于检测 gap 和抑制重复；Aeron Position 用于定位 Archive
字节流，两者不能互相替代。

## 目录

```text
src/main/java/.../
  api/            REST 请求、响应和错误契约
  application/    异步任务生命周期与并发控制
  aeron/          Archive 客户端和有界回放
  checkpoint/     原子 Checkpoint 持久化
  codec/          SBE 编解码适配
  config/         Spring Boot 外部化配置
  domain/         回放事件领域模型
  projection/     幂等应用、序列和哈希状态

src/main/resources/
  application.yml
  sbe/matching-events.xml

src/test/
  真实 ArchivingMediaDriver 和上游 Publisher 测试夹具
```

更详细的说明：

- [架构与服务边界](docs/architecture.md)
- [REST API 契约](docs/api.md)
- [部署与运维](docs/operations.md)
- [原始多进程 MVP 指南（历史参考）](docs/reference/original-mvp-guide.md)

## 当前范围

服务实现的是从 Archive 恢复下游投影的核心机制，不负责重新撮合，也不恢复撮合
引擎自身的 OrderBook。生产接入时可将 `ProjectionState` 替换为实际业务 Handler，
但必须保留 Position Checkpoint、业务序列幂等和有界回放语义。
