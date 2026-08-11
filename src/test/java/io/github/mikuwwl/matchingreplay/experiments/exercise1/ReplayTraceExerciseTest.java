package io.github.mikuwwl.matchingreplay.experiments.exercise1;

import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import io.github.mikuwwl.matchingreplay.projection.ProjectionState;
import io.github.mikuwwl.matchingreplay.support.EmbeddedArchiveFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Learning exercise 1: trace ten events through the core replay state.
 *
 * <p>The Embedded Archive Fixture supplies real encoded Aeron end positions.
 * The exercise applies the decoded domain events directly to ProjectionState
 * so that the state transition and checkpoint boundary remain easy to inspect.
 * It is intentionally separate from the production integration tests.</p>
 */
class ReplayTraceExerciseTest
{
    private static final String CHECKPOINT_KEY = "replay-trace-exercise";
    private static final int CHECKPOINT_EVERY = 5;

    @TempDir
    Path tempDirectory;

    @Test
    void tracesTenEventsAndPrintsReplayState() throws Exception
    {
        final List<MatchingEvent> events = LongStream.rangeClosed(1, 10)
            .mapToObj(ReplayTraceExerciseTest::event)
            .toList();

        try (EmbeddedArchiveFixture upstream =
            new EmbeddedArchiveFixture(tempDirectory.resolve("upstream")))
        {
            final EmbeddedArchiveFixture.Recording recording =
                upstream.record(events);
            final ReplayProperties properties = new ReplayProperties();
            properties.setAeronDirectory(tempDirectory.resolve("aeron"));
            properties.setCheckpointDirectory(tempDirectory.resolve("checkpoints"));
            final CheckpointRepository checkpoints =
                new CheckpointRepository(properties);
            final ProjectionState state = ProjectionState.from(
                Checkpoint.initial(
                    CHECKPOINT_KEY,
                    recording.recordingId(),
                    recording.startPosition()));

            assertNotEquals(
                events.get(0).eventSequence(),
                recording.positionAfterSequence(1),
                "Business Sequence must not be confused with Aeron Position");
            printHeader(recording);

            for (int index = 0; index < events.size(); index++)
            {
                final MatchingEvent event = events.get(index);
                final long sequence = event.eventSequence();
                final long position = recording.positionAfterSequence(sequence);
                final long digestBefore = state.replayDigest();

                state.apply(event, position);

                final long digestAfter = state.replayDigest();
                final long expectedDigestAfter =
                    recording.digestAfterSequence(sequence);
                assertEquals(expectedDigestAfter, digestAfter);

                String checkpointPosition = "-";
                if (sequence % CHECKPOINT_EVERY == 0)
                {
                    checkpoints.save(state.checkpoint(
                        CHECKPOINT_KEY,
                        recording.recordingId()));
                    checkpointPosition = Long.toString(
                        checkpoints.find(CHECKPOINT_KEY)
                            .orElseThrow()
                            .lastAppliedAeronPosition());
                }

                System.out.printf(
                    "%d | %d | %s | %s | %d | %s%n",
                    sequence,
                    position,
                    unsigned(digestBefore),
                    unsigned(digestAfter),
                    state.appliedEventsTotal(),
                    checkpointPosition);
            }

            assertEquals(10, state.lastAppliedEventSequence());
            assertEquals(recording.stopPosition(), state.lastAppliedAeronPosition());
            assertEquals(recording.expectedReplayDigest(), state.replayDigest());
            assertEquals(10, state.appliedEventsTotal());
            assertEquals(
                recording.positionAfterSequence(10),
                checkpoints.find(CHECKPOINT_KEY)
                    .orElseThrow()
                    .lastAppliedAeronPosition());
        }
    }

    private static void printHeader(
        final EmbeddedArchiveFixture.Recording recording)
    {
        System.out.println();
        System.out.println("Replay Exercise 1");
        System.out.println("recordingId=" + recording.recordingId());
        System.out.println("checkpointEvery=" + CHECKPOINT_EVERY);
        System.out.println(
            "Sequence | Aeron Position | Digest Before | Digest After | " +
                "Applied Count | Checkpoint Position");
    }

    private static String unsigned(final long value)
    {
        return Long.toUnsignedString(value);
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
            sequence % 2 == 0 ? Side.BUY : Side.SELL,
            100_000 + sequence,
            10 + sequence,
            10 + sequence);
    }
}
