# REST API

Base path: `/api/v1/replays`

## Start replay

```http
POST /api/v1/replays
Content-Type: application/json

{
  "recordingId": 42,
  "checkpointKey": "orders-projection",
  "stopPosition": 1349472,
  "expectedLastEventSequence": 12425,
  "expectedStateHash": "18013645834701933210",
  "correlationId": "incident-20260802"
}
```

Response: `202 Accepted`

```json
{
  "jobId": "844aa1ef-8c6f-4b49-b5f3-99450dc39a53",
  "state": "QUEUED",
  "command": {
    "recordingId": 42,
    "checkpointKey": "orders-projection",
    "stopPosition": 1349472,
    "expectedLastEventSequence": 12425,
    "expectedStateHash": "18013645834701933210",
    "correlationId": "incident-20260802"
  },
  "acceptedAt": "2026-08-02T00:00:00Z",
  "startedAt": null,
  "completedAt": null,
  "result": null,
  "error": null
}
```

Fields:

| Field | Required | Meaning |
|---|---:|---|
| `recordingId` | yes | Exact Aeron Archive recording |
| `checkpointKey` | no | Durable state key; defaults to `default` |
| `stopPosition` | no | Exclusive Aeron end Position for the bounded replay; defaults to the available position captured at job start |
| `expectedLastEventSequence` | yes | Expected business sequence at completion; cannot be omitted |
| `expectedStateHash` | yes | Expected unsigned 64-bit hash as a decimal string; cannot be omitted |
| `correlationId` | no | Caller trace or incident identifier |

`checkpointKey` must match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`.

## Get one replay

```http
GET /api/v1/replays/{jobId}
```

Terminal job states:

- `SUCCEEDED`: replay completed and both mandatory expectations matched.
- `VERIFICATION_FAILED`: replay completed, but the sequence or hash did not
  match.
- `FAILED`: Archive access, decoding, gap detection, timeout, or persistence
  failed.

Successful result:

```json
{
  "state": "SUCCEEDED",
  "result": {
    "recordingId": 42,
    "checkpointKey": "orders-projection",
    "replayStartPosition": 434080,
    "replayStopPosition": 1349472,
    "firstRecoveredSequence": 4001,
    "lastRecoveredSequence": 12425,
    "finalSequence": 12425,
    "appliedEvents": 12425,
    "gaps": 0,
    "duplicates": 0,
    "stateHash": "18013645834701933210",
    "replayDurationMs": 142,
    "verificationPassed": true
  }
}
```

The hash is always a string so JavaScript clients do not lose 64-bit precision.

## List in-memory job status

```http
GET /api/v1/replays
```

Job status is process-local operational state. Durable recovery state is the
checkpoint file. A service restart loses old HTTP job records but does not lose
the replay resume position.

## Errors

Errors use RFC 9457 `application/problem+json`.

| HTTP status | Cause |
|---:|---|
| 400 | Invalid command or unsigned hash |
| 404 | Unknown job ID |
| 409 | The same `checkpointKey` already has an active job |
| 503 | Replay worker queue is full |
