# Aeron Archive Replay Service

This repository contains a single-deployment Spring Boot replay service. It
coordinates and executes replay jobs without embedding the matching engine,
event producer, Media Driver, Aeron Archive, or consumer as separate
applications in the repository.

> This is an independent example based on public Aeron APIs. It does not
> contain or claim to reproduce proprietary OKX source code or confidential
> architecture.

## System boundary

```text
┌──────────────────────── Upstream Systems ──────────────────┐
│ Matching / Event Service                                  │
│   └─ SBE MatchingEvent ──> Aeron ──> Aeron Archive        │
└─────────────────────────────┬──────────────────────────────┘
                              │ recordingId + Position
                              ▼
┌──────────────────── Aeron Replay Service ──────────────────┐
│ POST /api/v1/replays                                      │
│   └─ ReplayJobManager (async, serialized per checkpoint)   │
│       └─ AeronReplayCoordinator                            │
│           ├─ connects to an external Media Driver/Archive  │
│           ├─ replays a bounded recordingId + Position      │
│           ├─ decodes SBE and checks gaps/duplicates        │
│           └─ atomically replaces checkpoints and verifies  │
│              the final projection hash                    │
└────────────────────────────────────────────────────────────┘
```

The repository produces one Maven artifact and one deployable process:

```text
aeron-replay-service-1.0.0-SNAPSHOT.jar
```

The fixtures that start an `ArchivingMediaDriver` and publish test events exist
only under `src/test`. They are not included in the production JAR and are
never invoked by production code.

## Quick start

Java 21 is required. Build the service and run all tests:

```powershell
.\scripts\build.ps1
```

Connect the service to an existing Media Driver and Aeron Archive:

```powershell
.\scripts\run-service.ps1 `
  -AeronDirectory "D:\aeron\driver" `
  -CheckpointDirectory ".\runtime\checkpoints" `
  -Port 8080
```

Start a replay:

```powershell
.\scripts\start-replay.ps1 `
  -RecordingId 42 `
  -CheckpointKey "orders-projection" `
  -StopPosition 1349472 `
  -ExpectedLastEventSequence 12425 `
  -ExpectedStateHash "18013645834701933210" `
  -CorrelationId "incident-20260802"
```

Query replay jobs and service health:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/replays/{jobId}
Invoke-RestMethod http://localhost:8080/api/v1/replays
Invoke-RestMethod http://localhost:8080/actuator/health
```

## Replay request semantics

`recordingId` is required. The service never guesses which Recording to use.
`checkpointKey` identifies an independent recovery state, and only one replay
job may be active for the same key at a time.

- With an existing checkpoint, replay resumes from
  `lastAppliedAeronPosition`.
- Without a checkpoint, replay starts from the Recording's `startPosition`,
  and the first business sequence is expected to be 1.
- If `stopPosition` is omitted, the job snapshots the currently available
  `recordingPosition` or `stopPosition` when it starts.
- `expectedLastEventSequence` and `expectedStateHash` are both required. If
  either value does not match, the job ends as `VERIFICATION_FAILED`; final
  state verification cannot be skipped.
- If replay execution fails, the job ends as `FAILED`, while the checkpoint
  remains at the last successfully and atomically replaced state.

A checkpoint stores both business progress and Archive stream progress:

```properties
recordingId=42
lastAppliedAeronPosition=434080
lastAppliedEventSequence=4000
stateHash=...
```

The business `eventSequence` detects gaps and suppresses duplicate effects.
The Aeron Position locates the Archive byte stream. These values have different
semantics and must never be used interchangeably.

The Archive `recordingPosition` used here only indicates that Archive reports
the Position as recorded and available as a replay boundary. It does not prove
that a storage device has completed a durable flush, nor that the data has been
replicated or cluster-committed. The integration fixture waits for
`recordingPosition >= publicationPosition` solely to produce deterministic test
input. This is not a description of an OKX production durability strategy.

## Repository layout

```text
src/main/java/.../
  api/            REST request, response, and error contracts
  application/    asynchronous job lifecycle and concurrency control
  aeron/          Archive client and bounded replay coordination
  checkpoint/     atomic checkpoint replacement
  codec/          SBE encoding and decoding adapters
  config/         Spring Boot external configuration
  domain/         replay event domain model
  projection/     idempotent application, sequencing, and state hashing

src/main/resources/
  application.yml
  sbe/matching-events.xml

src/test/
  real ArchivingMediaDriver and upstream publisher test fixtures
```

Additional documentation:

- [Architecture and service boundary](docs/architecture.md)
- [REST API contract](docs/api.md)
- [Deployment and operations](docs/operations.md)
- [Six-point replay review report](docs/replay-six-point-review.md)
- [Original multi-process MVP guide (historical reference)](docs/reference/original-mvp-guide.md)

## Scope

The service implements the core mechanism for rebuilding a downstream
projection from Archive. It does not re-run matching logic and does not restore
the matching engine's OrderBook.

A production integration may replace `ProjectionState` with its business
handler, but it must preserve bounded replay, Aeron Position checkpointing,
business-sequence idempotency, and final-state verification.
