# Architecture

## Deployment model

The repository produces one Spring Boot process: `aeron-replay-service`.
Matching, event publication, the Media Driver, and Aeron Archive are upstream
runtime dependencies, not sibling applications in this repository.

```text
                    control plane
Operator / upstream coordinator
            │
            │ POST recordingId, checkpointKey, stopPosition, expectations
            ▼
┌─────────────────────────────────────────────────────────────┐
│ Spring Boot Aeron Replay Service                            │
│                                                             │
│ ReplayController                                            │
│      │ 202 + jobId                                          │
│      ▼                                                      │
│ ReplayJobManager ── one active job per checkpointKey        │
│      │                                                      │
│      ▼                                                      │
│ AeronReplayCoordinator                                      │
│      ├─ CheckpointRepository                                │
│      ├─ generated SBE decoder                               │
│      └─ ProjectionState                                     │
└───────────────┬─────────────────────────────────────────────┘
                │ Aeron Archive control + replay stream
                ▼
┌─────────────────────────────────────────────────────────────┐
│ Upstream runtime                                            │
│ Media Driver <──> Aeron Archive Recording                   │
│                       ▲                                     │
│                       │ SBE matching-event stream           │
│               Matching / event service                      │
└─────────────────────────────────────────────────────────────┘
```

## Ownership

| Concern | Owner |
|---|---|
| Matching and order-book state | Upstream matching service |
| Event publication and negative `offer()` handling | Upstream event service |
| Media Driver lifecycle | Deployment platform / upstream runtime |
| Archive catalog and segments | Aeron Archive |
| Replay admission and job status | This service |
| Replay SBE decoding | This service |
| Projection checkpoint | This service |
| Expected final sequence and hash | Calling coordinator/upstream metadata |

The production artifact never launches an `ArchivingMediaDriver`. The real
Archive is embedded only by the integration test.

## Replay sequence

```text
Caller             Replay Service          Checkpoint       Aeron Archive
  │ POST command         │                      │                  │
  ├─────────────────────>│ validate + enqueue   │                  │
  │<────── 202 jobId ────┤                      │                  │
  │                      │ read(checkpointKey)  │                  │
  │                      ├─────────────────────>│                  │
  │                      │<── state + position ─┤                  │
  │                      │ get recording range                     │
  │                      ├────────────────────────────────────────>│
  │                      │ startReplay(recordingId, position, len) │
  │                      ├────────────────────────────────────────>│
  │                      │<──────────── SBE fragments ─────────────┤
  │                      │ decode/apply/check sequence             │
  │                      │ atomic replace      │                  │
  │                      ├─────────────────────>│                  │
  │                      │ stop replay session                    │
  │                      ├────────────────────────────────────────>│
  │ GET jobId            │                      │                  │
  ├─────────────────────>│                      │                  │
  │<── result + proof ────┤                      │                  │
```

The replay stop is bounded. If the request omits `stopPosition`, the service
captures the currently available recording position once at job start; it does
not chase a live recording forever.

## Consistency rules

1. A command always names one `recordingId`.
2. An existing checkpoint must contain the same `recordingId`.
3. Its Aeron Position must be inside the recording and requested replay range.
4. `eventSequence == last + 1` applies a business effect.
5. `eventSequence <= last` is an idempotently suppressed duplicate.
6. `eventSequence > last + 1` fails immediately as a gap.
7. The checkpoint is atomically replaced only after application.
8. Supplied final sequence and unsigned state hash must match for a
   `SUCCEEDED` result.

## Extension seam

`ProjectionState` is the sample event applier and verification state. In a real
integration, replace or wrap it with the target domain handler while preserving:

- decode before dispatch;
- idempotency by business sequence;
- checkpoint after successful effects;
- Aeron `Header.position()` as the resume position;
- a bounded stop position supplied or snapshotted for the job.
