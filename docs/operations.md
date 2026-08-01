# Operations

## Build

```powershell
.\mvnw.cmd -ntp clean verify
```

The build generates Java SBE codecs under
`target/generated-sources/sbe`, executes unit/API tests, boots the Spring
context, and runs a real `ArchivingMediaDriver` integration test.

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

Checkpoint files are the durable service state. Back them up with the same care
as the upstream recording metadata. Do not edit them manually.

To deliberately reset local development checkpoints:

```powershell
.\scripts\clean-data.ps1 -Confirm
```

Resetting a checkpoint causes the next job for that key to replay from the
recording start and expect the first business sequence to be 1.

## Failure behavior

- A gap or corrupt SBE message fails the job immediately.
- A timeout leaves the last periodically persisted checkpoint intact.
- A final checkpoint is written only after the requested replay range is
  consumed.
- A verification mismatch is distinct from an execution failure.
- Jobs for the same checkpoint key are rejected with HTTP 409.
