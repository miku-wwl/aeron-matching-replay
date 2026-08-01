# Demo Runbook

## Automated

Prerequisites: Java 21 and PowerShell. Maven itself is downloaded/pinned by the
wrapper.

```powershell
.\scripts\run-demo.ps1
```

The script:

1. validates and cleans only the repository's `runtime` subdirectories;
2. performs a Maven `clean install`, including the real-Archive integration test;
3. starts the Archive and waits for `ARCHIVE_READY`;
4. starts the live consumer and waits for `CONSUMER_READY`;
5. starts the engine;
6. requires the consumer process to exit with code 77;
7. waits for `ENGINE_FINISHED` after Archive catch-up;
8. runs bounded replay and requires `status=PASS`;
9. terminates the Archive process tree in `finally`.

Failures print the relevant log tails.

Evidence is retained under:

```text
runtime/logs/archive.log
runtime/logs/consumer-live.log
runtime/logs/engine.log
runtime/logs/replay.log
runtime/checkpoints/asset-projection.checkpoint
runtime/manifests/current-run.properties
```

## Manual

Clean and build:

```powershell
.\scripts\clean-data.ps1
.\scripts\build.ps1
```

Then use separate terminals:

```powershell
.\scripts\run-archive.ps1
.\scripts\run-consumer-crash.ps1
.\scripts\run-engine.ps1
.\scripts\run-replay.ps1
```

Keep the Archive terminal running through replay. Afterward:

```powershell
.\scripts\inspect-archive.ps1
```

## Expected markers

```text
ARCHIVE_READY
CONSUMER_READY
SIMULATED_CRASH
exitCode=77
ENGINE_FINISHED
REPLAY_COMPLETED
gaps=0
duplicates=0
status=PASS
```
