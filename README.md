# Aeron Archive Replay Reference

[![Replay Verification](https://github.com/miku-wwl/aeron-matching-replay/actions/workflows/replay-verification.yml/badge.svg)](https://github.com/miku-wwl/aeron-matching-replay/actions/workflows/replay-verification.yml)

An Aeron Archive–based replay and recovery reference implementation for
SBE-encoded matching event streams, with bounded replay, position-based
checkpointing, sequence validation, crash recovery, deterministic verification,
and observable replay progress.

This repository is one Java 21 Spring Boot service. It cooperates with an
upstream Aeron Media Driver and Archive in normal operation; tests start a real
embedded Archive so the complete workflow is reproducible.

## What this project demonstrates

```text
Capture recordingId and bounded stop Position
                    |
                    v
           Load progress checkpoint
                    |
                    v
       Start real Aeron Archive replay
                    |
                    v
        Decode Maven-generated SBE codecs
                    |
                    v
       Validate business eventSequence
          /                       \
    duplicate                  next event
        |                          |
advance Position        apply deterministic digest
          \                       /
                    v
 publish progress and atomically checkpoint Position
                    |
                    v
        resume safely after a hard crash
                    |
                    v
 verify final sequence + digest, then write proof
```

The service uses the Aeron Archive replay API. It does not simulate replay by
reading recording files.

## Core invariants

- **Replay boundary:** only fragments up to the captured stop Position are
  consumed. Data appended later is outside that run.
- **Sequence:** each newly applied event must have
  `eventSequence = lastAppliedEventSequence + 1`.
- **Duplicate:** an already-applied sequence advances the consumed Aeron
  Position, but does not change the digest or applied-event count.
- **Checkpoint:** `Header.position()` is saved only after the complete fragment
  has been decoded and handled. It is never confused with the fragment start.
- **Crash recovery:** the next process resumes at the last atomically persisted
  Aeron Position. Business `eventSequence` remains a separate value.
- **Verification:** an immutable, attempt-specific completion proof is created
  only after both the expected final sequence and expected replay digest match.
  A later replay using the same checkpoint key cannot overwrite it.

## One-command demonstration

Prerequisites: JDK 21. Maven is supplied by the wrapper.

```powershell
.\scripts\demo-replay.ps1
```

The command starts a real embedded Media Driver and Archive, records 1,000
Maven-SBE-encoded events, performs an uninterrupted replay, terminates a child
JVM with `Runtime.halt(77)` at checkpoint sequence 400, and resumes in a fresh
coordinator. Output includes recording and boundary Positions, the crash
checkpoint, the first sequence after restart, both digests, counters, and:

```text
[1/6] Started embedded Aeron Archive
[2/6] Recorded 1,000 SBE events
[3/6] Completed uninterrupted replay
[4/6] Halted replay process after checkpoint sequence 400
[5/6] Resumed from saved Aeron position
[6/6] Final replay digest matched uninterrupted replay

REPLAY WORKFLOW: PASS
```

The proof is stronger than a graceful-restart test: the child cannot run normal
shutdown hooks. After restart, the first newly applied event is sequence 401,
the cumulative applied count is 1,000, duplicates are zero, and the resumed
digest equals the uninterrupted digest.

## API example

Start the service against an existing Media Driver and Archive:

```powershell
.\scripts\run-service.ps1 -AeronDirectory "D:\aeron\driver"
```

Submit a bounded replay:

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

The request returns `202 Accepted`. Poll
`GET /api/v1/replays/{jobId}`. A running response exposes `currentPosition`,
`progressPercent`, `lastEventSequence`, per-run counters, latest checkpoint
Position, throughput, and `lastProgressAt`. Terminal state is `VERIFIED`,
`VERIFICATION_FAILED`, or `FAILED`; failures include a stable code and
diagnostic fields rather than requiring message parsing.
Each job also exposes a distinct `attemptId`; these two IDs identify its
immutable completion proof.

Counter names are explicit:

- `appliedEventsThisRun` and `duplicatesThisRun` describe this execution.
- `appliedEventsTotal` and `duplicatesTotal` include the loaded checkpoint.
- `sequenceGapsThisRun` belongs only to this execution attempt.

See [API reference](docs/api.md) for complete request and response examples.

## Failure scenarios proved by tests

| Scenario | Invariant proved |
|---|---|
| Bounded replay | Appended events beyond the captured stop Position are excluded |
| Live bounded replay | Recording remains live while events after the captured boundary are ignored |
| Duplicate sequence | Position advances; digest and applied count do not |
| Sequence gap | Fails with `SEQUENCE_GAP`; checkpoint remains at the last good event |
| Invalid/future SBE | Validates schema, template, version, and acting block length before application |
| Verification mismatch | Progress remains valid but no completion proof is created or overwritten |
| Repeated/no-op verification | Every successful attempt gets a separate immutable proof |
| Hard process crash | Fresh process resumes at saved Position and matches uninterrupted digest |
| No progress | Full Coordinator/job flow returns structured timeout and preserves its last checkpoint |
| Schema evolution | Current decoder reads v1 and v2, and rejects unsupported future versions |

## Replay digest

The rolling FNV-64 digest is a compact deterministic event-stream proof, not a
database or OrderBook state hash. Its canonical field order is:

```text
eventSequence, eventType, orderId, contraOrderId, tradeId,
symbolId, side, price, quantity, remainingQuantity
```

Timestamp, schema version, `sourceId`, and all Aeron transport metadata are
excluded. The current digest is persisted in the progress checkpoint, making
the chain resumable.

## Explicit non-goals

This project does not restore a matching engine or OrderBook, replay commands
into matching logic, reconstruct maker/taker decisions, implement trading risk
controls, or provide PostgreSQL/Kafka/Kubernetes/authentication/UI features.
The small matching-event model exists only to produce realistic replay input.
The implementation must not be read as a description of any proprietary OKX
production architecture.

## Build and test

```powershell
git clone https://github.com/miku-wwl/aeron-matching-replay.git
Set-Location aeron-matching-replay
.\mvnw.cmd --version
```

```powershell
.\mvnw.cmd -ntp clean verify
```

The Maven Wrapper downloads Maven 3.9.9 from Maven Central; no regional mirror
configuration is required.

`generate-sources` runs the official SBE tool against
`src/main/resources/sbe/matching-events.xml`; generated Java is written only to
`target/generated-sources/sbe`. Deleting `target/` is safe because Maven
regenerates the codecs.

The full suite uses an actual `ArchivingMediaDriver`, covers the focused failure
matrix, and runs the hard-crash child-JVM test. CI runs the same command on
pushes to `main` and pull requests.

## Operations and observability

Progress checkpoints live in `runtime/checkpoints`; verified completion proofs
are immutable files under
`runtime/checkpoints/completion-proofs/{checkpointKey}/{attemptId}.properties`.
The checkpoint
cadence counts all successfully processed messages, including duplicates,
because their consumed Aeron Position must be recoverable.

Actuator exposes replay-focused Micrometer metrics at `/actuator/metrics`.
Lifecycle logs carry MDC `jobId`, `attemptId`, `correlationId`, and
`recordingId` and avoid per-event logging. See:

- [Architecture](docs/architecture.md)
- [Operations](docs/operations.md)
- [Six-point replay review](docs/replay-six-point-review.md)
