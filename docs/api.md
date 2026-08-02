# Replay API

The API creates asynchronous, bounded replay jobs. Job history is in memory;
progress checkpoints and completion proofs survive service restarts.

## Start a replay

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

| Field | Required | Meaning |
|---|---:|---|
| `recordingId` | yes | Aeron Archive recording identifier |
| `checkpointKey` | no | Projection/recovery identity; defaults to `default` |
| `stopPosition` | no | Bounded end Position; current available Position is captured once when omitted |
| `expectedLastEventSequence` | yes | Mandatory final business sequence expectation |
| `expectedReplayDigest` | yes | Mandatory unsigned 64-bit digest, represented as a decimal string |
| `correlationId` | no | Caller correlation value, at most 128 characters |

The response is `202 Accepted`, contains a generated `jobId` and `attemptId`,
and has a `Location` header for the job resource. Only one active job may use
a `checkpointKey`; a conflict returns HTTP 409.

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

## Inspect jobs

```http
GET /api/v1/replays/{jobId}
GET /api/v1/replays
```

States are:

- `QUEUED`: accepted but not yet executing.
- `RUNNING`: Archive replay is active.
- `VERIFIED`: the bounded replay completed and both expectations matched.
- `VERIFICATION_FAILED`: the boundary was reached, but an expectation differed.
- `FAILED`: execution stopped because of a structured replay failure.

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

`currentPosition` is monotonic and bounded by `replayStopPosition`.
`lastCheckpointPosition` advances only after a successful atomic checkpoint
write. Progress reaches 100% at the requested boundary.

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

Every verified attempt creates
`completion-proofs/{checkpointKey}/{attemptId}.properties`. Proofs contain both
IDs, correlation ID, replay range, resume flag, final sequence/digest, status,
and completion time. They are immutable: reusing a checkpoint key creates a
new proof, while reusing an existing attempt ID fails explicitly.

## Failure response

Expected replay failures expose a stable `failure.code` and nullable diagnostic
fields:

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

Supported failure codes:

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

Codec failures populate `templateId`, `schemaId`, `actingVersion`,
`actingBlockLength`, `minimumSupportedBlockLength`, and `fragmentPosition`
when available. A known schema with an unknown template returns
`UNSUPPORTED_TEMPLATE`; an unknown schema ID or future version returns
`UNSUPPORTED_SCHEMA`. No-progress failures provide `currentPosition`,
`replayStopPosition`, `lastAppliedEventSequence`,
`timeSinceLastProgressMillis`, and `configuredNoProgressTimeoutMillis`.
Verification failures expose expected and actual sequences and unsigned
digests.

## Counter semantics

- `appliedEventsThisRun`: newly applied business events during this process run.
- `appliedEventsTotal`: cumulative applied count loaded from and saved to the
  progress checkpoint.
- `duplicatesThisRun`: duplicates suppressed during this run.
- `duplicatesTotal`: cumulative duplicate count.
- `sequenceGapsThisRun`: gaps observed during this run; a gap terminates the
  replay immediately.

Duplicates count as processed messages for checkpoint cadence because their
Aeron Positions still have to become recoverable.
