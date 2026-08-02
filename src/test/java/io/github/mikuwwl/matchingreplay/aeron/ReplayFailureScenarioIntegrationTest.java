package io.github.mikuwwl.matchingreplay.aeron;

import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.checkpoint.CompletionProof;
import io.github.mikuwwl.matchingreplay.checkpoint.CompletionProofRepository;
import io.github.mikuwwl.matchingreplay.codec.MatchingEventSbeEncoder;
import io.github.mikuwwl.matchingreplay.codec.generated.MessageHeaderEncoder;
import io.github.mikuwwl.matchingreplay.codec.generated.OrderAcceptedDecoder;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.ReplayDigest;
import io.github.mikuwwl.matchingreplay.domain.Side;
import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import io.github.mikuwwl.matchingreplay.support.EmbeddedArchiveFixture;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayFailureScenarioIntegrationTest
{
    @TempDir
    Path tempDirectory;

    @Test
    void boundedReplayStopsAtCapturedPosition()
    {
        try (EmbeddedArchiveFixture archive = fixture("bounded"))
        {
            final List<MatchingEvent> events = events(1, 10);
            final EmbeddedArchiveFixture.Recording recording = archive.record(events);
            final long boundedSequence = 5;
            final long boundedStop = recording.positionAfterSequence(boundedSequence);
            final long boundedDigest = recording.digestAfterSequence(boundedSequence);
            final ReplayProperties properties = properties(archive, "bounded");

            final ReplayResult result = coordinator(properties).replay(new ReplayCommand(
                recording.recordingId(),
                "bounded",
                boundedStop,
                boundedSequence,
                boundedDigest,
                "bounded-test"));

            assertTrue(result.verificationPassed());
            assertEquals(boundedStop, result.replayStopPosition());
            assertEquals(boundedSequence, result.finalEventSequence());
            assertEquals(boundedSequence, result.appliedEventsThisRun());
            assertEquals(
                boundedStop,
                new CompletionProofRepository(properties)
                    .findByAttemptId("bounded", result.attemptId())
                    .orElseThrow()
                    .replayStopPosition());
        }
    }

    @Test
    void boundedReplayDoesNotFollowEventsAppendedAfterCapturedStopPosition()
    {
        try (EmbeddedArchiveFixture archive = fixture("live-bounded");
            EmbeddedArchiveFixture.LiveRecording live =
                archive.startLiveRecording())
        {
            live.publish(events(1, 5));
            final EmbeddedArchiveFixture.Recording boundary =
                live.captureBoundary();
            live.publish(events(6, 10));
            assertTrue(live.isLive());
            final ReplayProperties properties = properties(archive, "live-bounded");
            final AtomicReference<ReplayProgress> finalProgress =
                new AtomicReference<>();

            final ReplayResult result = coordinator(properties).replay(
                new ReplayCommand(
                    boundary.recordingId(),
                    "live-bounded",
                    boundary.stopPosition(),
                    5,
                    boundary.expectedReplayDigest(),
                    "live-bounded-test"),
                finalProgress::set);

            assertTrue(result.verificationPassed());
            assertEquals(5, result.finalEventSequence());
            assertEquals(5, result.appliedEventsThisRun());
            assertEquals(boundary.stopPosition(), result.replayStopPosition());
            assertEquals(boundary.expectedReplayDigest(), result.finalReplayDigest());
            assertEquals(100.0, finalProgress.get().progressPercent());
            assertEquals(
                boundary.stopPosition(),
                new CompletionProofRepository(properties)
                    .findByAttemptId("live-bounded", result.attemptId())
                    .orElseThrow()
                    .replayStopPosition());
            assertTrue(live.isLive());
        }
    }

    @Test
    void noOpReplayCreatesSeparateProof()
    {
        try (EmbeddedArchiveFixture archive = fixture("no-op-proof"))
        {
            final EmbeddedArchiveFixture.Recording recording =
                archive.record(events(1, 3));
            final ReplayProperties properties = properties(archive, "no-op-proof");
            final AeronReplayCoordinator coordinator = coordinator(properties);
            final ReplayCommand command = new ReplayCommand(
                recording.recordingId(),
                "no-op-proof",
                recording.stopPosition(),
                recording.eventCount(),
                recording.expectedReplayDigest(),
                "no-op-proof-test");

            final ReplayResult first = coordinator.replay(command);
            final ReplayResult noOp = coordinator.replay(command);

            assertTrue(first.verificationPassed());
            assertTrue(noOp.verificationPassed());
            assertEquals(recording.startPosition(), first.replayStartPosition());
            assertEquals(recording.stopPosition(), noOp.replayStartPosition());
            assertEquals(0, noOp.appliedEventsThisRun());
            final List<CompletionProof> proofs =
                new CompletionProofRepository(properties)
                    .findByCheckpointKey("no-op-proof");
            assertEquals(2, proofs.size());
            assertFalse(proofs.stream()
                .filter(proof -> proof.attemptId().equals(first.attemptId()))
                .findFirst()
                .orElseThrow()
                .resumedFromCheckpoint());
            assertTrue(proofs.stream()
                .filter(proof -> proof.attemptId().equals(noOp.attemptId()))
                .findFirst()
                .orElseThrow()
                .resumedFromCheckpoint());
        }
    }

    @Test
    void duplicateDoesNotChangeReplayDigest()
    {
        try (EmbeddedArchiveFixture archive = fixture("duplicate"))
        {
            final MatchingEvent first = event(1);
            final MatchingEvent second = event(2);
            final MatchingEvent duplicateSecond = event(2);
            final MatchingEvent third = event(3);
            final EmbeddedArchiveFixture.Recording recording = archive.record(
                List.of(first, second, duplicateSecond, third));
            long expectedDigest = ReplayDigest.INITIAL_VALUE;
            expectedDigest = ReplayDigest.mixEvent(expectedDigest, first);
            expectedDigest = ReplayDigest.mixEvent(expectedDigest, second);
            expectedDigest = ReplayDigest.mixEvent(expectedDigest, third);
            final ReplayProperties properties = properties(archive, "duplicate");
            properties.setCheckpointEveryProcessedMessages(3);
            final CountingCheckpointRepository checkpoints =
                new CountingCheckpointRepository(properties);
            final AeronReplayCoordinator coordinator = new AeronReplayCoordinator(
                new AeronArchiveClientFactory(properties),
                checkpoints,
                new CompletionProofRepository(properties),
                properties);

            final ReplayResult result = coordinator.replay(new ReplayCommand(
                recording.recordingId(),
                "duplicate",
                recording.stopPosition(),
                3,
                expectedDigest,
                "duplicate-test"));

            assertTrue(result.verificationPassed());
            assertEquals(3, result.appliedEventsThisRun());
            assertEquals(1, result.duplicatesThisRun());
            assertEquals(expectedDigest, result.finalReplayDigest());
            final Checkpoint checkpoint =
                new CheckpointRepository(properties).find("duplicate").orElseThrow();
            assertEquals(recording.stopPosition(), checkpoint.lastAppliedAeronPosition());
            assertEquals(expectedDigest, checkpoint.replayDigest());
            assertEquals(recording.startPosition(), checkpoints.savedPositions.getFirst());
            assertEquals(recording.eventEndPositions().get(2), checkpoints.savedPositions.get(1));
            assertEquals(3, checkpoints.savedPositions.size());
        }
    }

    @Test
    void gapLeavesCheckpointAtLastGoodMessage()
    {
        try (EmbeddedArchiveFixture archive = fixture("gap"))
        {
            final List<MatchingEvent> events = List.of(event(1), event(2), event(4));
            final EmbeddedArchiveFixture.Recording recording = archive.record(events);
            final ReplayProperties properties = properties(archive, "gap");
            properties.setCheckpointEveryProcessedMessages(1);

            final ReplayException exception = assertThrows(
                ReplayException.class,
                () -> coordinator(properties).replay(new ReplayCommand(
                    recording.recordingId(),
                    "gap",
                    recording.stopPosition(),
                    4,
                    recording.expectedReplayDigest(),
                    "gap-test")));

            assertEquals(ReplayFailureCode.SEQUENCE_GAP, exception.failure().code());
            assertEquals(2, exception.failure().lastAppliedEventSequence());
            assertEquals(4, exception.failure().receivedEventSequence());
            final Checkpoint checkpoint =
                new CheckpointRepository(properties).find("gap").orElseThrow();
            assertEquals(2, checkpoint.lastAppliedEventSequence());
            assertEquals(recording.eventEndPositions().get(1), checkpoint.lastAppliedAeronPosition());
            assertTrue(
                new CompletionProofRepository(properties)
                    .findByCheckpointKey("gap")
                    .isEmpty());
        }
    }

    @Test
    void invalidSbeDoesNotAdvanceCheckpoint()
    {
        try (EmbeddedArchiveFixture archive = fixture("invalid-sbe"))
        {
            final byte[] valid = encoded(event(1));
            final byte[] unsupportedFutureVersion = encoded(event(2));
            new MessageHeaderEncoder()
                .wrap(new UnsafeBuffer(unsupportedFutureVersion), 0)
                .version(99);
            final EmbeddedArchiveFixture.Recording recording =
                archive.recordRaw(List.of(valid, unsupportedFutureVersion));
            final ReplayProperties properties = properties(archive, "invalid-sbe");
            properties.setCheckpointEveryProcessedMessages(1);

            final ReplayException exception = assertThrows(
                ReplayException.class,
                () -> coordinator(properties).replay(new ReplayCommand(
                    recording.recordingId(),
                    "invalid-sbe",
                    recording.stopPosition(),
                    2,
                    ReplayDigest.INITIAL_VALUE,
                    "invalid-sbe-test")));

            assertEquals(ReplayFailureCode.UNSUPPORTED_SCHEMA, exception.failure().code());
            assertEquals(1, exception.failure().templateId());
            assertEquals(100, exception.failure().schemaId());
            assertEquals(99, exception.failure().actingVersion());
            assertEquals(
                recording.eventEndPositions().get(1),
                exception.failure().fragmentPosition());
            final Checkpoint checkpoint =
                new CheckpointRepository(properties).find("invalid-sbe").orElseThrow();
            assertEquals(1, checkpoint.lastAppliedEventSequence());
            assertEquals(recording.eventEndPositions().getFirst(), checkpoint.lastAppliedAeronPosition());
            assertTrue(
                new CompletionProofRepository(properties)
                    .findByCheckpointKey("invalid-sbe")
                    .isEmpty());
        }
    }

    @Test
    void invalidActingBlockLengthDoesNotAdvanceCheckpoint()
    {
        try (EmbeddedArchiveFixture archive = fixture("invalid-block-length"))
        {
            final byte[] valid = encoded(event(1));
            final byte[] invalid = encoded(event(2));
            final int minimumBlockLength = OrderAcceptedDecoder.BLOCK_LENGTH;
            new MessageHeaderEncoder()
                .wrap(new UnsafeBuffer(invalid), 0)
                .blockLength(minimumBlockLength - 1);
            final EmbeddedArchiveFixture.Recording recording =
                archive.recordRaw(List.of(valid, invalid));
            final ReplayProperties properties =
                properties(archive, "invalid-block-length");
            properties.setCheckpointEveryProcessedMessages(1);

            final ReplayException exception = assertThrows(
                ReplayException.class,
                () -> coordinator(properties).replay(new ReplayCommand(
                    recording.recordingId(),
                    "invalid-block-length",
                    recording.stopPosition(),
                    2,
                    ReplayDigest.INITIAL_VALUE,
                    "invalid-block-length-test")));

            assertEquals(ReplayFailureCode.SBE_DECODE_FAILED, exception.failure().code());
            assertEquals(1, exception.failure().templateId());
            assertEquals(100, exception.failure().schemaId());
            assertEquals(2, exception.failure().actingVersion());
            assertEquals(
                minimumBlockLength - 1,
                exception.failure().actingBlockLength());
            assertEquals(
                minimumBlockLength,
                exception.failure().minimumSupportedBlockLength());
            assertEquals(
                recording.eventEndPositions().get(1),
                exception.failure().fragmentPosition());
            final Checkpoint checkpoint = new CheckpointRepository(properties)
                .find("invalid-block-length")
                .orElseThrow();
            assertEquals(1, checkpoint.lastAppliedEventSequence());
            assertEquals(
                recording.eventEndPositions().getFirst(),
                checkpoint.lastAppliedAeronPosition());
            assertTrue(
                new CompletionProofRepository(properties)
                    .findByCheckpointKey("invalid-block-length")
                    .isEmpty());
        }
    }

    @Test
    void verificationMismatchDoesNotCreateOrOverwriteCompletionProof()
    {
        try (EmbeddedArchiveFixture archive = fixture("verification"))
        {
            final EmbeddedArchiveFixture.Recording recording = archive.record(events(1, 5));
            final ReplayProperties properties = properties(archive, "verification");
            final AeronReplayCoordinator coordinator = coordinator(properties);
            final CompletionProofRepository proofs = new CompletionProofRepository(properties);

            final ReplayResult mismatch = coordinator.replay(new ReplayCommand(
                recording.recordingId(),
                "mismatch-only",
                recording.stopPosition(),
                recording.eventCount(),
                recording.expectedReplayDigest() + 1,
                "mismatch-test"));

            assertFalse(mismatch.verificationPassed());
            assertTrue(proofs.findByCheckpointKey("mismatch-only").isEmpty());
            assertEquals(
                recording.stopPosition(),
                new CheckpointRepository(properties)
                    .find("mismatch-only")
                    .orElseThrow()
                    .lastAppliedAeronPosition());

            final ReplayResult verified = coordinator.replay(new ReplayCommand(
                recording.recordingId(),
                "existing-proof",
                recording.stopPosition(),
                recording.eventCount(),
                recording.expectedReplayDigest(),
                "verified-test"));
            assertTrue(verified.verificationPassed());
            final CompletionProof original = proofs
                .findByAttemptId("existing-proof", verified.attemptId())
                .orElseThrow();

            final ReplayResult laterMismatch = coordinator.replay(new ReplayCommand(
                recording.recordingId(),
                "existing-proof",
                recording.stopPosition(),
                recording.eventCount(),
                recording.expectedReplayDigest() + 1,
                "later-mismatch-test"));

            assertFalse(laterMismatch.verificationPassed());
            assertEquals(
                original,
                proofs.findByAttemptId("existing-proof", verified.attemptId()).orElseThrow());
            assertEquals(1, proofs.findByCheckpointKey("existing-proof").size());
        }
    }

    private EmbeddedArchiveFixture fixture(final String name)
    {
        return new EmbeddedArchiveFixture(tempDirectory.resolve(name + "-archive"));
    }

    private ReplayProperties properties(
        final EmbeddedArchiveFixture archive,
        final String name)
    {
        return archive.replayProperties(tempDirectory.resolve(name + "-checkpoints"));
    }

    private static AeronReplayCoordinator coordinator(final ReplayProperties properties)
    {
        return new AeronReplayCoordinator(
            new AeronArchiveClientFactory(properties),
            new CheckpointRepository(properties),
            new CompletionProofRepository(properties),
            properties);
    }

    private static List<MatchingEvent> events(
        final long firstSequence,
        final long lastSequence)
    {
        return LongStream.rangeClosed(firstSequence, lastSequence)
            .mapToObj(ReplayFailureScenarioIntegrationTest::event)
            .toList();
    }

    private static MatchingEvent event(final long sequence)
    {
        return new MatchingEvent(
            (short)2,
            EventType.ORDER_ACCEPTED,
            sequence,
            1_000_000 + sequence,
            10_000 + sequence,
            0,
            0,
            1,
            Side.BUY,
            100_000 + sequence,
            10 + sequence,
            10 + sequence,
            1);
    }

    private static byte[] encoded(final MatchingEvent event)
    {
        final MatchingEventSbeEncoder encoder = new MatchingEventSbeEncoder();
        final UnsafeBuffer buffer = new UnsafeBuffer(
            new byte[MatchingEventSbeEncoder.MAX_ENCODED_LENGTH]);
        final int length = encoder.encode(event, buffer, 0);
        final byte[] encoded = new byte[length];
        buffer.getBytes(0, encoded);
        return encoded;
    }

    private static final class CountingCheckpointRepository extends CheckpointRepository
    {
        private final List<Long> savedPositions = new ArrayList<>();

        private CountingCheckpointRepository(final ReplayProperties properties)
        {
            super(properties);
        }

        @Override
        public void save(final Checkpoint checkpoint)
        {
            super.save(checkpoint);
            savedPositions.add(checkpoint.lastAppliedAeronPosition());
        }
    }
}
