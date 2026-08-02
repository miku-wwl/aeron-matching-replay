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
7. The checkpoint is atomically replaced only after application; filesystems
   without atomic move support fail the checkpoint write.
8. The mandatory final sequence and unsigned state hash must both match for a
   `SUCCEEDED` result.

## Position and checkpoint semantics

`ReplayFragmentHandler` decodes a completed Aeron message, applies or
idempotently suppresses its business event, and only then passes
`Header.position()` into `ProjectionState`. The saved value is therefore the
end Position of the fully processed message, not the fragment's starting
offset. A restart asks Archive to replay from that saved end Position, so the
next message is the first not represented by the checkpoint.

`eventSequence` and Aeron Position are deliberately separate fields:

| Value | Meaning | Used for |
|---|---|---|
| `eventSequence` | Business ordering identity | Gap detection and idempotency |
| `lastAppliedAeronPosition` | Archive byte-stream progress | Replay start Position |

The checkpoint writer forces the temporary file contents and requires a
same-filesystem atomic move to replace the prior file. It fails rather than
silently performing a non-atomic replacement. This protects the service's
process-crash recovery invariant on supported filesystems. It does not claim
that the parent-directory entry was forced, that a storage device completed a
power-loss-safe flush, or that the checkpoint was replicated.

When replacing the in-memory sample `ProjectionState` with an external
database or service, the business effect, deduplication record, and checkpoint
must be made one atomic recovery unit (for example with a transaction or
Inbox). An atomic checkpoint file alone cannot make an unrelated external side
effect atomic.

## Archive durability boundary

Four different observations must not be collapsed into one:

1. `publication.offer(...) > 0`: the Publication accepted the message and
   returned a stream Position.
2. `recordingPosition >= publishedPosition`: Archive reports recording progress
   through that Position, making it an appropriate available replay bound.
3. Device-durable: the relevant files and metadata have completed the storage
   guarantees required to survive the selected failure model.
4. Replicated or cluster-committed: another durability policy has acknowledged
   the event.

The service uses the active `recordingPosition` (or stopped
`stopPosition`) to bound bytes available from Archive. It does not turn that
counter into an `fsync`, replication, or cluster-commit claim. The integration
fixture waits for the counter to reach the publisher's final Position only to
make the test deterministic.

This repository makes no claim about the exact OKX production strategy.
Depending on a system's requirements, production designs may use asynchronous
recording, Archive replication, Aeron Cluster log semantics, another journal,
or a combination of them.

## Extension seam

`ProjectionState` is the sample event applier and verification state. In a real
integration, replace or wrap it with the target domain handler while preserving:

- decode before dispatch;
- idempotency by business sequence;
- checkpoint after successful effects;
- Aeron `Header.position()` as the resume position;
- a bounded stop position supplied or snapshotted for the job.
