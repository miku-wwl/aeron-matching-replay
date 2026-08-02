# Operations

## Build

```powershell
.\mvnw.cmd -ntp clean verify
```

The build generates Java SBE codecs under
`target/generated-sources/sbe`, executes unit/API tests, boots the Spring
context, and runs a real `ArchivingMediaDriver` integration test. That test
also terminates a child replay JVM with `Runtime.halt(77)` and verifies recovery
against an uninterrupted replay.

## Package and run

```powershell
.\mvnw.cmd -ntp package
java `
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED `
  --add-opens java.base/java.util.zip=ALL-UNNAMED `
  -jar .\target\aeron-replay-service-1.0.0-SNAPSHOT.jar
```

The PowerShell helper supplies the required JVM opens:

```powershell
.\scripts\run-service.ps1 -AeronDirectory "D:\aeron\driver"
```

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `AERON_DIR` | JVM temp `aeron-default` | Media Driver directory |
| `REPLAY_CHECKPOINT_DIR` | `./runtime/checkpoints` | Durable service checkpoints |
| `REPLAY_CHANNEL` | `aeron:ipc` | Archive replay channel |
| `REPLAY_STREAM_ID` | `1002` | Replay stream |
| `REPLAY_TIMEOUT` | `20s` | Connection/poll deadline |
| `REPLAY_FRAGMENT_LIMIT` | `20` | Fragments per subscription poll |
| `REPLAY_CHECKPOINT_EVERY` | `100` | Applied fragments between checkpoint writes |
| `REPLAY_WORKER_COUNT` | `1` | Concurrent jobs with distinct checkpoint keys |
| `REPLAY_QUEUE_CAPACITY` | `100` | Pending task capacity |
| `ARCHIVE_CONTROL_REQUEST_CHANNEL` | `aeron:ipc?term-length=64k` | Archive request channel |
| `ARCHIVE_CONTROL_REQUEST_STREAM_ID` | `10` | Archive request stream |
| `ARCHIVE_CONTROL_RESPONSE_CHANNEL` | `aeron:ipc` | Archive response channel |
| `SERVER_PORT` | `8080` | HTTP port |

IPC defaults assume the replay service shares a Media Driver host/runtime with
the Archive control client. For a UDP Archive control plane, set explicit
request and response endpoints appropriate for the deployment.

## Health and task diagnosis

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/v1/replays
```

The actuator health endpoint proves the service process is ready. Archive
connectivity is checked lazily by each job and reported as `FAILED`; this avoids
making an intentionally idle replay service unready merely because an upstream
maintenance window is in progress.

## Checkpoint handling

Checkpoint files are the service's persistent recovery state. Back them up with
the same care as the upstream recording metadata. Do not edit them manually.

Each write forces a temporary file and requires atomic replacement on the same
filesystem. If atomic move is unsupported, the job fails instead of degrading
to a non-atomic overwrite. The implementation does not force the parent
directory and does not promise survival of every storage-controller or
power-loss failure; select and validate the filesystem/storage policy against
the deployment's failure model.

To deliberately reset local development checkpoints:

```powershell
.\scripts\clean-data.ps1 -Confirm
```

Resetting a checkpoint causes the next job for that key to replay from the
recording start and expect the first business sequence to be 1.

## Publication, recording, and durability

These operational milestones are distinct:

| Milestone | What it proves | What it does not prove |
|---|---|---|
| Publication accepts an offer | Aeron assigned a stream Position | Archive has recorded it |
| Archive counter reaches the Position | The Position is reported recorded/available | Device `fsync`, replication, or cluster commit |
| Storage durability policy acknowledges | The configured device failure model is covered | A remote replica committed |
| Replication/cluster policy acknowledges | The configured replicated failure model is covered | Any stronger policy not explicitly configured |

The service reads `recordingPosition` for an active Recording and
`stopPosition` for a stopped Recording. The integration fixture waits until the
counter catches the final Publication Position solely to prevent a race in the
test. This wait is an MVP/test trade-off, not a statement that OKX production
does the same. Production systems may choose asynchronous recording,
replication, Aeron Cluster, or another journal according to their own latency
and loss budget.

## Failure behavior

- A gap or corrupt SBE message fails the job immediately.
- A timeout leaves the last periodically persisted checkpoint intact.
- A final checkpoint is written only after the requested replay range is
  consumed.
- A verification mismatch is distinct from an execution failure.
- Jobs for the same checkpoint key are rejected with HTTP 409.
