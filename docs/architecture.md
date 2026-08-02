# Architecture

## Deployment model

The repository builds one Java 21 Spring Boot process:
`aeron-replay-service`. Matching, event publication, the Media Driver, and
Aeron Archive remain upstream responsibilities. Production code connects to an
existing Archive; only tests start an embedded `ArchivingMediaDriver`.

```text
Replay request / manifest
 recordingId, optional stopPosition, expected sequence + digest
                              |
                              v
                    ReplayController (202)
                              |
                              v
             ReplayJobManager + live progress + MDC
                              |
                              v
                   AeronReplayCoordinator
           /                  |                  \
progress checkpoint   generated SBE decode   completion proof
           \                  |                  /
                              v
               real Aeron Archive replay API
                              |
                              v
                   upstream recording runtime
```

One active job is allowed per `checkpointKey`. Multiple keys may execute up to
the configured worker count.

## Replay workflow

1. Resolve the recording start and currently available end Position through
   Aeron Archive.
2. Validate or capture the bounded stop Position once.
3. Load the progress checkpoint and validate its `recordingId` and Position.
4. Call `AeronArchive.startReplay(recordingId, startPosition, length, ...)`.
5. Poll the replay `Subscription`; decode every fragment with Maven-generated
   SBE codecs.
6. Classify the business sequence as duplicate, next event, or gap.
7. Apply the deterministic digest only for a next event.
8. Advance consumed progress to `Header.position()` only after complete
   handling.
9. Publish immutable live progress and periodically persist an atomic progress
   checkpoint.
10. At the bounded stop, write the final progress checkpoint and verify the
    expected sequence and digest.
11. Atomically create a separate immutable
    `{checkpointKey}/{attemptId}.properties` proof only on a match, then mark
    the job `VERIFIED`.

If verification fails, the completed progress checkpoint is retained because
processed projection effects may already be committed. No completion proof is
created. Each later successful attempt receives a new `attemptId`, so neither a
normal retry nor a no-op verification can overwrite historical evidence.

## Position and sequence invariants

| Value | Domain | Purpose |
|---|---|---|
| `eventSequence` | business event order | idempotency and gap detection |
| `lastAppliedAeronPosition` | transport byte stream | Archive restart Position |
| `stopPosition` | transport byte stream | immutable replay boundary |
| `replayDigest` | canonical event stream | deterministic verification |

These values are never substituted for one another. `Header.position()` is the
end Position of the fully assembled and handled Aeron message. A decode, schema,
or sequence failure therefore cannot advance the checkpoint past the invalid
fragment.

Sequence rules:

```text
eventSequence <= last sequence      duplicate: no digest/application change
eventSequence == last sequence + 1  apply: update digest and applied count
eventSequence >  last sequence + 1  fail immediately with SEQUENCE_GAP
```

A duplicate still advances consumed Aeron Position and the processed-message
checkpoint cadence.

## Persistent artifacts

### Progress checkpoint

Stored under `runtime/checkpoints` and used only for restart:

```properties
checkpointKey=orders-projection
recordingId=42
lastAppliedEventSequence=12425
lastAppliedAeronPosition=1349472
appliedEventsTotal=12425
duplicatesTotal=2
replayDigest=18013645834701933210
updatedAt=2026-08-02T00:00:00Z
```

### Completion proof

Stored only after verification:

```text
runtime/checkpoints/completion-proofs/
  orders-projection/
    5c40f71e-6417-4ac3-a775-0a18542027db.properties
```

```properties
jobId=e97a6293-9a21-4954-a657-f407ca271b40
attemptId=5c40f71e-6417-4ac3-a775-0a18542027db
correlationId=recovery-2026-08-02
checkpointKey=orders-projection
recordingId=42
replayStartPosition=434080
replayStopPosition=1349472
finalEventSequence=12425
finalReplayDigest=18013645834701933210
resumedFromCheckpoint=true
verificationStatus=VERIFIED
completedAt=2026-08-02T00:00:01Z
```

Checkpoint replacement uses a forced temporary file and same-filesystem atomic
move. Proof creation uses a forced temporary inode followed by an atomic hard
link; link creation fails if that attempt's target already exists. This
preserves immutable attempt history. These are process-crash invariants on a
supporting filesystem, not claims that the parent directory, device, or remote
replica has committed.

## Digest definition

The resumable rolling FNV-64 digest uses this exact canonical order:

```text
eventSequence
eventType
orderId
contraOrderId
tradeId
symbolId
side
price
quantity
remainingQuantity
```

Timestamp, SBE schema version, optional v2 `sourceId`, and Aeron transport
metadata are excluded. The digest proves deterministic handling of this event
stream; it is not a complete OrderBook or database state hash.

## Schema evolution

SBE codecs are generated in Maven `generate-sources` from schema version 2.
The current decoder accepts:

- v1 messages, where `sourceId` is absent and defaults to zero;
- v2 messages, where optional `sourceId` is present;
- no future acting version, which fails with `UNSUPPORTED_SCHEMA`.

Before a generated decoder is wrapped, `actingBlockLength` is validated against
the generated v1 or v2 minimum for that specific template. A short/corrupt
block returns `SBE_DECODE_FAILED` with both lengths and the fragment end
Position; the checkpoint remains at the preceding valid fragment. Unknown
templates use `UNSUPPORTED_TEMPLATE`, separately from schema-level failures.

The field is deliberately excluded from the digest so reading the same
historical business event through either supported encoding does not change its
proof.

## Progress and timeout model

The handler emits immutable `ReplayProgress` snapshots. Position is monotonic,
bounded by the stop Position, and drives a no-progress watchdog. Any Position
advance refreshes the watchdog, so a healthy large replay may take longer than
`noProgressTimeout`. An optional `maximumReplayDuration` is a distinct absolute
limit. The Coordinator uses an injectable monotonic clock so timeout behavior
can be tested deterministically across the full job lifecycle.

## Archive durability boundary

Do not collapse these observations:

1. `publication.offer(...) > 0`: Aeron accepted the message and assigned a
   stream Position.
2. `recordingPosition >= publishedPosition`: Archive reports the bytes as
   recorded and available for replay.
3. Device durable: storage has met the configured power-loss contract.
4. Replicated/cluster committed: a separate durability policy has acknowledged
   the data.

The integration fixture waits for step 2 only to remove a test race. This
latency/reliability trade-off is suitable for the demonstration and is not a
claim about OKX production. A production design may use asynchronous recording,
replication, Aeron Cluster, or another journal.

## External projection boundary

`ProjectionState` is an in-memory reference consumer. If it is replaced by a
database or remote side effect, the effect, deduplication/Inbox record, and
checkpoint need one transactional recovery unit. Atomic checkpoint files alone
cannot make unrelated external writes idempotent.
