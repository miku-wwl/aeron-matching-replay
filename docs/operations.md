# Operations

## Build, verify, and demonstrate

```powershell
.\mvnw.cmd --version
.\mvnw.cmd -ntp clean verify
.\scripts\demo-replay.ps1
```

The wrapper downloads Maven 3.9.9 from Maven Central.
`clean verify` regenerates SBE Java under `target/generated-sources/sbe`, runs
unit and API tests, starts a real embedded `ArchivingMediaDriver` for the
integration matrix, and terminates a child replay JVM with `Runtime.halt(77)`
to prove hard-crash recovery. The demo runs the focused crash workflow and
exits non-zero on failure.

Deleting `target/` is safe. Generated SBE encoders and decoders are build output
and are recreated during `generate-sources`.

## Package and run

```powershell
.\mvnw.cmd -ntp package
.\scripts\run-service.ps1 -AeronDirectory "D:\aeron\driver"
```

The helper supplies the JVM module opens required by Aeron. The production
service expects an upstream Media Driver and Archive; it never embeds them.

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `AERON_DIR` | JVM temp `aeron-default` | Media Driver directory |
| `REPLAY_CHECKPOINT_DIR` | `./runtime/checkpoints` | Progress checkpoints and completion proofs |
| `REPLAY_CHANNEL` | `aeron:ipc` | Archive replay channel |
| `REPLAY_STREAM_ID` | `1002` | Replay stream |
| `REPLAY_NO_PROGRESS_TIMEOUT` | `20s` | Maximum time without Position advance |
| `ARCHIVE_REQUEST_TIMEOUT` | `20s` | Archive connect/control request timeout |
| `REPLAY_FRAGMENT_LIMIT` | `20` | Fragments per subscription poll |
| `REPLAY_CHECKPOINT_EVERY_PROCESSED_MESSAGES` | `100` | Handled messages between checkpoint writes |
| `REPLAY_WORKER_COUNT` | `1` | Concurrent jobs with distinct checkpoint keys |
| `REPLAY_QUEUE_CAPACITY` | `100` | Pending task capacity |
| `ARCHIVE_CONTROL_REQUEST_CHANNEL` | `aeron:ipc?term-length=64k` | Archive request channel |
| `ARCHIVE_CONTROL_REQUEST_STREAM_ID` | `10` | Archive request stream |
| `ARCHIVE_CONTROL_RESPONSE_CHANNEL` | `aeron:ipc` | Archive response channel |
| `SERVER_PORT` | `8080` | HTTP port |

`maximumReplayDuration` is intentionally unset. Configure a distinct absolute
limit only when policy requires one, for example:

```powershell
$env:MATCHING_REPLAY_MAXIMUM_REPLAY_DURATION = "30m"
.\scripts\run-service.ps1 -AeronDirectory "D:\aeron\driver"
```

The checkpoint cadence counts all successfully handled SBE messages, including
duplicates. A duplicate has no business effect but its consumed Aeron Position
must still become durable.

IPC defaults assume the service shares a Media Driver runtime with the Archive
control client. For UDP control, configure deployment-specific request and
response endpoints.

## Health, jobs, and progress

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/v1/replays
Invoke-RestMethod http://localhost:8080/actuator/metrics
```

Archive connectivity is checked when a job starts, so an idle service can
remain healthy during an upstream maintenance window. The job resource exposes
monotonic current Position, stop Position, percentage, last sequence, per-run
counters, checkpoint Position, throughput, and last progress time.

## Structured lifecycle logs

Lifecycle event names are:

```text
REPLAY_REQUEST_ACCEPTED
REPLAY_STARTED
REPLAY_RESUMED
REPLAY_CHECKPOINTED
REPLAY_BOUNDARY_REACHED
REPLAY_VERIFIED
REPLAY_VERIFICATION_FAILED
REPLAY_FAILED
```

Every lifecycle event contains `jobId`; MDC carries `jobId`, `attemptId`,
`correlationId`, and `recordingId`. Position, sequence, counters, failure code,
and duration are included where relevant. Logging occurs at
lifecycle/checkpoint boundaries, not for every event.

## Micrometer metrics

Actuator exposes the following low-cardinality meters:

| Meter | Meaning |
|---|---|
| `replay.jobs` with terminal `status` tag | Completed jobs by status |
| `replay.duration` | Execution time |
| `replay.events.applied` | Newly applied events |
| `replay.duplicates` | Suppressed duplicates |
| `replay.sequence.gaps` | Detected sequence gaps |
| `replay.checkpoint.writes` | Successful progress checkpoint writes |
| `replay.checkpoint.write.failures` | Failed checkpoint writes |
| `replay.position.lag` | Aggregate remaining Position for active jobs |
| `replay.no.progress.timeouts` | Jobs stopped by no-progress watchdog |

No meter uses `jobId`, `correlationId`, `recordingId`, or `checkpointKey` as a
label.

Example:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/metrics/replay.jobs
Invoke-RestMethod http://localhost:8080/actuator/metrics/replay.position.lag
```

## Replay state handling

Progress checkpoints are stored directly in the configured directory.
Completion proofs are separate immutable files under
`completion-proofs/{checkpointKey}/{attemptId}.properties`. Do not edit either
artifact manually.

Checkpoint writes force a temporary file and require atomic replacement on the
same filesystem. Proof writes force complete temporary content, then atomically
create a hard link that fails if the attempt already exists. Unsupported atomic
filesystem behavior fails the job instead of silently degrading. This does not
guarantee parent-directory `fsync`, device durability, or replication; storage
must be selected for the deployment failure model.

To deliberately remove local development replay state:

```powershell
.\scripts\clean-data.ps1 -Confirm
```

This removes both progress checkpoints and completion proofs but preserves
`runtime/checkpoints/.gitkeep`.

## Failure behavior

- A decode/schema/template/block-length failure or sequence gap stops
  immediately and cannot advance the checkpoint past the invalid fragment.
- A healthy replay can run longer than `REPLAY_NO_PROGRESS_TIMEOUT` while its
  Position continues to advance.
- A stalled replay fails with `NO_PROGRESS_TIMEOUT`, elapsed/configured timeout
  values, and its last successfully processed Position/sequence.
- A final progress checkpoint is written when the bounded Position is reached.
- A verification mismatch retains that checkpoint, returns expected/actual
  values, and creates no new completion proof.
- A new immutable completion proof is created only for a `VERIFIED` attempt.
  Replays sharing a checkpoint key—including no-op verification—retain
  separate proof files.

## Publication, recording, and durability

Publication acceptance, Archive recording progress, device durability, and
replicated/cluster commit are distinct milestones. The integration fixture
waits until `recordingPosition >= publishedPosition` solely to remove a test
race. This is an MVP verification trade-off, not a description of OKX
production. Production systems may choose asynchronous Archive recording,
replication, Aeron Cluster log semantics, or another durability policy.
