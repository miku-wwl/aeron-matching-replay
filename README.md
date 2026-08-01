# Matching Engine → Aeron Archive → Replay Lab

A Java 21/Maven reconstruction of a matching-event recording and downstream
projection-recovery pipeline. It deliberately stays small: a deterministic
single-threaded order book emits SBE messages through an Aeron
`ExclusivePublication`; Aeron Archive records the stream; a consumer persists
both its business sequence and Aeron byte position; and a bounded Archive
replay restores the projection after a hard process crash.

> This repository is an independent educational reconstruction based on public Aeron APIs and the author's prior exposure to matching-engine replay work. It does not contain or claim to reproduce proprietary OKX source code or confidential architecture.

## What the demo proves

The one-command demo establishes this bounded reliability statement:

> A consumer that crashes after durably checkpointing an Aeron stream position can recover the missing matching events from Aeron Archive and converge to the same projection state as uninterrupted consumption.

The proof checks all of the following:

- the live consumer terminates via `Runtime.halt(77)` after sequence 4000;
- its checkpoint contains sequence 4000 and the post-fragment
  `Header.position()`;
- the engine continues publishing and waits for the Archive recording position;
- replay starts at that saved Aeron position, not at position zero;
- recovered business sequences are continuous and have no duplicate business
  effects;
- the recovered deterministic FNV-1a state hash equals the uninterrupted
  reference hash written by the engine.

Run it from PowerShell:

```powershell
.\scripts\run-demo.ps1
```

Build and unit/integration test independently:

```powershell
.\mvnw.cmd clean verify
```

The demo performs a `clean install` because the foreground helper scripts
execute individual Maven modules from the local reactor artifacts.

## Architecture in one page

```text
SyntheticOrderFeed
  -> single-threaded price/time-priority OrderBook
  -> MatchingEvent
  -> build-generated SBE Encoder + Agrona UnsafeBuffer
  -> Aeron ExclusivePublication (aeron:ipc, stream 1001)
       -> live Projection Consumer
       -> Aeron Archive Recording (the matching Event Log)
            -> bounded startReplay(checkpointPosition, length)
            -> replay Subscription (stream 1002)
            -> recovered Projection
```

Only `ArchiveNodeMain` launches a Media Driver. Every other process is an Aeron
client connected to the same directory.

## Aeron, Archive, and the Event Log

**Aeron is transport.** A Publication, Media Driver, and Subscription move
messages with low latency. A successful `publication.offer()` means Aeron
accepted the message and returned a new stream position.

**Aeron Archive is durable recording and replay.** In this project, the Archive
Recording itself is the append-only Matching Event Log; there is no parallel
text-file or custom journal pretending to be the log. Before the engine exits,
it polls until the recording position reaches the final publication position.

These are four different milestones:

```text
transport accepted -> Archive recorded -> consumer applied -> consumer checkpointed
```

The configured Archive file/catalog sync level is 1 for the learning demo.
Production durability semantics still depend on filesystem, device, operating
system, and deployment guarantees.

## Replay is not re-matching

The `OrderBook` is the matching core's in-memory state. The `ProjectionState`
is downstream derived state. Replay reads already-recorded matching events and
rebuilds the projection; it does not regenerate synthetic orders and does not
call the order book again. This MVP does not recover the matching engine's own
order-book state.

## Business sequence versus Aeron position

`eventSequence` is a consecutive business identifier (`1, 2, 3...`) used for
gap detection and idempotency. Aeron position is a byte position affected by
frame headers, alignment, padding, MTU, and fragmentation; it is not an event
count.

The checkpoint atomically saves both:

```properties
lastAppliedEventSequence=4000
lastAppliedAeronPosition=...
```

The consumer checkpoints the `Header.position()` after successful application.
Archive replay begins at that end position, so the next event should be 4001.

## At-least-once behavior

Crashing after applying an event but before replacing the checkpoint can cause
that event to be delivered again. The projection therefore applies:

```text
sequence == last + 1 -> apply
sequence <= last     -> suppress duplicate
sequence > last + 1  -> fail immediately on gap
```

The main demo starts at an exact post-event checkpoint and requires zero
duplicates. A real-Archive integration test intentionally replays from one
position earlier and proves that the duplicate changes neither the projection
hash nor its business effects.

## Project layout

| Module | Responsibility |
|---|---|
| `replay-domain` | Orders, events, enums, deterministic hashing |
| `replay-codec` | SBE XML schema, generated codecs, adapters/dispatch |
| `orderbook-engine` | Price-time-priority book and deterministic feed |
| `aeron-infrastructure` | Archive node, publication, checkpoints, replay |
| `matching-engine-app` | Engine process |
| `projection-consumer-app` | Live consumer and hard crash |
| `replay-coordinator-app` | Bounded position replay and verification |
| `replay-integration-tests` | Real ArchivingMediaDriver record/replay tests |

Generated SBE sources live under
`replay-codec/target/generated-sources/sbe` and are never hand-edited.

## Useful commands

```powershell
.\scripts\clean-data.ps1
.\scripts\build.ps1
.\scripts\run-archive.ps1
.\scripts\run-consumer-crash.ps1
.\scripts\run-engine.ps1
.\scripts\run-replay.ps1
.\scripts\inspect-archive.ps1
```

Run the Archive script in one terminal and the consumer/engine/replay scripts
in the order shown in [docs/demo-runbook.md](docs/demo-runbook.md).

All JVMs need:

```text
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens java.base/java.util.zip=ALL-UNNAMED
```

The scripts supply these through `MAVEN_OPTS`.

## Scope boundary and future work

This MVP does not solve the window in which the in-memory order book changes
but the matching event has not yet been accepted by the Aeron Publication.
Closing that window needs a command log, synchronous journal, Aeron Cluster, or
a more complete persisted matching state machine.

Future work, intentionally not implemented here: `ReplayMerge`, live catch-up
and merge, snapshots plus replay, order-book recovery, replicated Archive,
multi-symbol partitioning, CPU affinity, latency histograms, and chaos testing.
