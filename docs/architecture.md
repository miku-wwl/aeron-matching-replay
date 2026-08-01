# Architecture

```text
┌────────────────────┐
│ SyntheticOrderFeed │ deterministic seed/order count
└─────────┬──────────┘
          ▼
┌─────────────────────────────┐
│ MatchingEngine / OrderBook  │ one owner thread
│ bids: descending prices     │
│ asks: ascending prices      │
│ each level: FIFO queue      │
└─────────┬───────────────────┘
          │ MatchingEvent + consecutive eventSequence
          ▼
┌─────────────────────────────┐
│ SBE generated codecs        │ schema is the source of truth
│ MutableDirectBuffer/        │
│ DirectBuffer underneath     │
└─────────┬───────────────────┘
          ▼
┌─────────────────────────────┐
│ ExclusivePublication       │ aeron:ipc, stream 1001
└──────────┬───────────┬──────┘
           │           │ local Archive subscription
           ▼           ▼
┌──────────────────┐  ┌───────────────────────────┐
│ Live Projection  │  │ Aeron Archive Recording   │
│ FragmentAssembler│  │ catalog + segment files   │
│ Header.position  │  └──────────────┬────────────┘
│ atomic checkpoint│                 │ bounded startReplay
└──────────────────┘                 ▼
                          ┌─────────────────────────┐
                          │ Replay Coordinator      │
                          │ stream 1002 + sessionId │
                          │ gap/duplicate/hash proof│
                          └─────────────────────────┘
```

## Process ownership

`ArchiveNodeMain` owns the sole `ArchivingMediaDriver`, which embeds the Media
Driver and Archive. Engine, consumer, replay coordinator, and inspector create
only Aeron clients and share `runtime/aeron`.

The Archive is configured with a local IPC control request channel. Its network
control channel remains configured for Archive validity, but normal lab clients
use the local channel. Live and replay event streams use IPC.

## State and persistence

- Order book: volatile matching state; not recovered by this MVP.
- Recording: durable matching Event Log used for replay.
- Manifest: identifies the exact recording and the engine's final reference
  sequence, positions, and hashes.
- Checkpoint: atomically stores the projection summary, sequence, and Aeron
  position.

No second append-only event log is created.

## Completion invariant

The bounded replay passes only when:

```text
checkpoint.recordingId == manifest.recordingId
checkpointPosition is inside [recordingStartPosition, replayStopPosition]
lastAppliedAeronPosition >= replayStopPosition
lastAppliedEventSequence == manifest.lastEventSequence
gapCount == 0
duplicateCount == 0
recoveredStateHash == manifest.expectedProjectionHash
```
