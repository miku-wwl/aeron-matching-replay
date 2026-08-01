# Verification Evidence

Final verification was performed on 2026-08-02 (Pacific/Auckland), using
Eclipse Temurin Java 21.0.6 and Maven Wrapper/Maven 3.9.9.

## Clean build and tests

Exact command:

```powershell
.\mvnw.cmd -ntp clean verify
```

Result:

```text
[INFO] Reactor Summary for Matching Engine Aeron Replay Lab 1.0.0-SNAPSHOT:
[INFO]
[INFO] Matching Engine Aeron Replay Lab ................... SUCCESS
[INFO] replay-domain ...................................... SUCCESS
[INFO] replay-codec ....................................... SUCCESS
[INFO] orderbook-engine ................................... SUCCESS
[INFO] aeron-infrastructure ............................... SUCCESS
[INFO] matching-engine-app ................................ SUCCESS
[INFO] projection-consumer-app ............................ SUCCESS
[INFO] replay-coordinator-app ............................. SUCCESS
[INFO] replay-integration-tests ........................... SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  16.923 s
[INFO] Finished at: 2026-08-02T10:35:35+12:00
```

Test inventory (20 tests, zero failures/errors/skips):

```text
OrderTest                         3
MatchingEventSbeCodecTest         5
OrderBookTest                     6
CheckpointStoreTest               2
ProjectionStateTest               3
RealArchiveReplayTest             1
```

`RealArchiveReplayTest` launched real instances of:

```text
ArchivingMediaDriver
AeronArchive
ExclusivePublication
Subscription + FragmentAssembler
startReplay(recordingId, startPosition, length, channel, streamId)
```

It published and fully replayed 1,000 real SBE/Aeron messages, replayed from
the position checkpointed after business sequence 400 (first recovered
sequence 401), deliberately replayed one position earlier to verify duplicate
suppression, rejected out-of-range positions, and compared uninterrupted and
recovered hashes.

## Dependency convergence

Exact command:

```powershell
.\mvnw.cmd dependency:tree
```

Relevant resolved lines across consuming modules:

```text
io.aeron:aeron-all:jar:1.52.2:compile
org.agrona:agrona:jar:2.4.1:compile
```

Maven Enforcer passed `dependencyConvergence` and
`banDuplicatePomDependencyVersions` for every module. The SBE build plugin uses
`uk.co.real-logic:sbe-tool:1.37.1`; generated code is recreated successfully
from an empty `target` during every clean build.

## Hard-crash recovery demo

Exact command:

```powershell
.\scripts\run-demo.ps1
```

Script result:

```text
DEMO_PASS
archiveLog=D:\workshop\aug\aeron-matching-replay\runtime\logs\archive.log
consumerLog=D:\workshop\aug\aeron-matching-replay\runtime\logs\consumer-live.log
engineLog=D:\workshop\aug\aeron-matching-replay\runtime\logs\engine.log
replayLog=D:\workshop\aug\aeron-matching-replay\runtime\logs\replay.log
checkpoint=D:\workshop\aug\aeron-matching-replay\runtime\checkpoints\asset-projection.checkpoint
manifest=D:\workshop\aug\aeron-matching-replay\runtime\manifests\current-run.properties
```

The script explicitly required the live consumer OS process exit code to equal
77 before continuing.

Archive:

```text
aeronDirectory=D:\workshop\aug\aeron-matching-replay\runtime\aeron
archiveDirectory=D:\workshop\aug\aeron-matching-replay\runtime\archive
ARCHIVE_READY
```

Live consumer and durable crash checkpoint:

```text
CONSUMER_READY
consumer=asset-projection
streamId=1001
SIMULATED_CRASH
consumer=asset-projection
recordingId=0
lastSequence=4000
checkpointPosition=434080
stateHash=2944803516154737765
exitCode=77
```

Engine and Archive catch-up:

```text
ENGINE_FINISHED
runId=2026-08-01T22:36:06.951954600Z
ordersSubmitted=5000
eventsPublished=12425
tradesCreated=4896
firstSequence=1
lastSequence=12425
publicationPosition=1349472
recordedPosition=1349472
recordingId=0
orderBookHash=721610185325335530
expectedProjectionHash=18013645834701933210
backPressureCount=0
```

Position-bounded Archive replay:

```text
REPLAY_COMPLETED
recordingId=0
replayStartPosition=434080
replayStopPosition=1349472
firstRecoveredSequence=4001
lastRecoveredSequence=12425
finalSequence=12425
gaps=0
duplicates=0
stateHash=18013645834701933210
replayDurationMs=24702
status=PASS
```

Final comparison:

```text
uninterrupted reference hash = 18013645834701933210
recovered projection hash    = 18013645834701933210
publication position         = 1349472
Archive recorded position    = 1349472
checkpoint/replay start      = 434080
first missing business event = 4001
final business event         = 12425
gap count                    = 0
duplicate count              = 0
```

Archive storage evidence:

```text
runtime/archive/0-0.rec          134217728 bytes
runtime/archive/archive.catalog   1048576 bytes
runtime/archive/archive-mark.dat  1056768 bytes
```

The final checkpoint was advanced by replay to:

```properties
appliedEventCount=12425
lastAppliedAeronPosition=1349472
lastAppliedEventSequence=12425
recordingId=0
duplicateEventCount=0
gapCount=0
stateHash=18013645834701933210
```
