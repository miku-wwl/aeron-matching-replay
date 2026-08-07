# Replay API

该 API 用于创建异步、具有明确边界的 Replay Job。Job 历史保存在内存中；Progress Checkpoint
和 Completion Proof 会在服务重启后保留。

## 发起 Replay

```http
POST /api/v1/replays
Content-Type: application/json

{
  "recordingId": 42,
  "checkpointKey": "orders-projection",
  "stopPosition": 1349472,
  "expectedLastEventSequence": 12425,
  "expectedReplayDigest": "18013645834701933210",
  "correlationId": "recovery-2026-08-02"
}
```

| 字段 | 必填 | 含义 |
|---|---:|---|
| `recordingId` | 是 | Aeron Archive 的 recording 标识。每个 Replay Command 都必须明确指定。 |
| `checkpointKey` | 否 | Projection/Recovery 的身份标识，默认值为 `default`。 |
| `stopPosition` | 否 | 本次 Replay 的结束 Position。不提供时，只在开始时捕获一次当前可用 Position。 |
| `expectedLastEventSequence` | 是 | 期望的最终业务 Sequence。 |
| `expectedReplayDigest` | 是 | 期望的 unsigned 64-bit Digest，以十进制字符串表示。 |
| `correlationId` | 否 | 调用方提供的关联标识，最长 128 个字符。 |

响应为 `202 Accepted`，包含生成的 `jobId` 和 `attemptId`，并通过 `Location` Header
指向 Job 资源。同一个 `checkpointKey` 同时只能有一个活跃 Job；冲突时返回 HTTP 409。

PowerShell:

```powershell
.\scripts\start-replay.ps1 `
  -RecordingId 42 `
  -CheckpointKey orders-projection `
  -StopPosition 1349472 `
  -ExpectedLastEventSequence 12425 `
  -ExpectedReplayDigest 18013645834701933210 `
  -CorrelationId recovery-2026-08-02
```

## 查看 Job

```http
GET /api/v1/replays/{jobId}
GET /api/v1/replays
```

Job 状态如下：

- `QUEUED`：请求已接受，但尚未开始执行。
- `RUNNING`：Archive Replay 正在进行。
- `VERIFIED`：已到达 Replay 边界，且两个期望值都匹配。
- `VERIFICATION_FAILED`：已到达边界，但至少一个期望值不匹配。
- `FAILED`：发生结构化 Replay Failure，执行提前停止。

Example running response:

```json
{
  "jobId": "e97a6293-9a21-4954-a657-f407ca271b40",
  "attemptId": "5c40f71e-6417-4ac3-a775-0a18542027db",
  "state": "RUNNING",
  "command": {
    "recordingId": 42,
    "checkpointKey": "orders-projection",
    "stopPosition": 1349472,
    "expectedLastEventSequence": 12425,
    "expectedReplayDigest": "18013645834701933210",
    "correlationId": "recovery-2026-08-02"
  },
  "progress": {
    "replayStartPosition": 434080,
    "currentPosition": 830400,
    "replayStopPosition": 1349472,
    "progressPercent": 43.3,
    "lastEventSequence": 7821,
    "appliedEventsThisRun": 3821,
    "duplicatesThisRun": 0,
    "lastCheckpointPosition": 811200,
    "eventsPerSecond": 184200,
    "lastProgressAt": "2026-08-02T00:00:00Z"
  },
  "result": null,
  "failure": null
}
```

`currentPosition` 单调递增，且不会超过 `replayStopPosition`。只有 Atomic Checkpoint
写入成功后，`lastCheckpointPosition` 才会前进。到达请求的 Replay 边界时，Progress
达到 100%。

Example verified result:

```json
{
  "state": "VERIFIED",
  "result": {
    "jobId": "e97a6293-9a21-4954-a657-f407ca271b40",
    "attemptId": "5c40f71e-6417-4ac3-a775-0a18542027db",
    "recordingId": 42,
    "checkpointKey": "orders-projection",
    "replayStartPosition": 434080,
    "replayStopPosition": 1349472,
    "firstAppliedEventSequenceThisRun": 4001,
    "lastAppliedEventSequenceThisRun": 12425,
    "finalEventSequence": 12425,
    "expectedLastEventSequence": 12425,
    "appliedEventsThisRun": 8425,
    "appliedEventsTotal": 12425,
    "duplicatesThisRun": 0,
    "duplicatesTotal": 2,
    "sequenceGapsThisRun": 0,
    "finalReplayDigest": "18013645834701933210",
    "expectedReplayDigest": "18013645834701933210",
    "replayDurationMs": 87,
    "verificationPassed": true
  },
  "failure": null
}
```

每个 `VERIFIED` Attempt 都会创建
`completion-proofs/{checkpointKey}/{attemptId}.properties`。Proof 包含两个 ID、
Correlation ID、Replay Range、是否从 Checkpoint 恢复、最终 Sequence/Digest、状态和完成时间。
Proof 不可修改：重复使用 `checkpointKey` 会生成新的 Proof；重复使用已有 `attemptId`
会明确失败。

## Failure Response

可预期的 Replay Failure 会返回稳定的 `failure.code`，以及可能为空的诊断字段：

```json
{
  "state": "FAILED",
  "progress": {
    "currentPosition": 434080,
    "replayStopPosition": 1349472,
    "lastEventSequence": 400
  },
  "result": null,
  "failure": {
    "code": "SEQUENCE_GAP",
    "message": "Expected sequence 401 but received 403",
    "recordingId": 42,
    "currentPosition": 434080,
    "replayStopPosition": 1349472,
    "lastAppliedEventSequence": 400,
    "receivedEventSequence": 403
  }
}
```

支持的 Failure Code：

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
COMPLETION_PROOF_CORRUPTED
VERIFICATION_MISMATCH
REPLAY_IMAGE_UNAVAILABLE
INTERNAL_ERROR
```

Codec Failure 会在可获得时填充 `templateId`、`schemaId`、`actingVersion`、
`actingBlockLength`, `minimumSupportedBlockLength`, and `fragmentPosition`
等字段。已知 Schema 中出现未知 Template 时返回 `UNSUPPORTED_TEMPLATE`；未知 Schema ID
或未来版本返回 `UNSUPPORTED_SCHEMA`。No-progress Failure 会提供 `currentPosition`、
`replayStopPosition`, `lastAppliedEventSequence`,
`timeSinceLastProgressMillis`, and `configuredNoProgressTimeoutMillis`.
Verification Failure 会同时暴露期望值和实际的 Sequence 与 unsigned Digest。

## Counter 语义

- `appliedEventsThisRun`：本次进程运行期间新应用的业务事件数。
- `appliedEventsTotal`：从 Progress Checkpoint 加载并再次保存的累计应用数。
- `duplicatesThisRun`：本次运行中被抑制的 Duplicate 数量。
- `duplicatesTotal`：累计 Duplicate 数量。
- `sequenceGapsThisRun`：本次运行发现的 Gap 数量；一旦发现 Gap，Replay 立即终止。

Duplicate 仍然算作已处理消息，因为它们对应的 Aeron Position 也必须进入可恢复的 Checkpoint。
