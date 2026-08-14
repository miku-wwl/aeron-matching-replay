package io.github.mikuwwl.matchingreplay.experiments.exercise2;

import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.ReplayDigest;
import io.github.mikuwwl.matchingreplay.domain.Side;
import io.github.mikuwwl.matchingreplay.projection.ProjectionState;
import io.github.mikuwwl.matchingreplay.support.EmbeddedArchiveFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Learning exercise 2: a duplicate business sequence still advances the
 * transport position, but does not change the business result.
 */
class DuplicateReplayExerciseTest
{
    private static final String CHECKPOINT_KEY = "duplicate-replay-exercise";

    @TempDir
    Path tempDirectory;

    @Test
    void duplicateAdvancesPositionButNotDigestOrAppliedCount() throws Exception
    {
        final List<MatchingEvent> events = List.of(
            event(1),
            event(2),
            event(3),
            event(3),
            event(4));

        try (EmbeddedArchiveFixture upstream =
            new EmbeddedArchiveFixture(tempDirectory.resolve("upstream")))
        {
            final EmbeddedArchiveFixture.Recording recording = upstream.record(events);
            final ReplayProperties properties = new ReplayProperties();
            properties.setAeronDirectory(tempDirectory.resolve("aeron"));
            properties.setCheckpointDirectory(tempDirectory.resolve("checkpoints"));
            final CheckpointRepository checkpoints = new CheckpointRepository(properties);
            final ProjectionState state = ProjectionState.from(
                Checkpoint.initial(
                    CHECKPOINT_KEY,
                    recording.recordingId(),
                    recording.startPosition()));

            long digestAfterFirstSequenceThree = 0;
            long positionAfterFirstSequenceThree = 0;
            long positionAfterDuplicate = 0;

            System.out.println();
            System.out.println("Replay Exercise 2");
            System.out.println("recordedSequences=1, 2, 3, 3, 4");
            System.out.println(
                "Index | Sequence | Aeron Position | Result | Digest Before | " +
                    "Digest After | Applied Count | Duplicate Count | Checkpoint Position");

            for (int index = 0; index < events.size(); index++)
            {
                final MatchingEvent event = events.get(index);
                final long position = recording.positionAfterEventIndex(index);
                final long digestBefore = state.replayDigest();
                final ProjectionState.ApplyResult result = state.apply(event, position);
                final long digestAfter = state.replayDigest();

                if (index == 2)
                {
                    digestAfterFirstSequenceThree = digestAfter;
                    positionAfterFirstSequenceThree = position;
                }
                if (index == 3)
                {
                    positionAfterDuplicate = position;
                    assertEquals(digestAfterFirstSequenceThree, digestBefore);
                    assertEquals(digestAfterFirstSequenceThree, digestAfter);
                }

                String checkpointPosition = "-";
                if (index == events.size() - 1)
                {
                    checkpoints.save(state.checkpoint(CHECKPOINT_KEY, recording.recordingId()));
                    checkpointPosition = Long.toString(
                        checkpoints.find(CHECKPOINT_KEY).orElseThrow().lastAppliedAeronPosition());
                }

                System.out.printf(
                    "%d | %d | %d | %s | %s | %s | %d | %d | %s%n",
                    index,
                    event.eventSequence(),
                    position,
                    result,
                    unsigned(digestBefore),
                    unsigned(digestAfter),
                    state.appliedEventsTotal(),
                    state.duplicatesTotal(),
                    checkpointPosition);
            }

            final long expectedDigestWithoutDuplicate = ReplayDigest.mixEvent(
                ReplayDigest.mixEvent(
                    ReplayDigest.mixEvent(
                        ReplayDigest.mixEvent(
                            ReplayDigest.INITIAL_VALUE,
                            events.get(0)),
                        events.get(1)),
                    events.get(2)),
                events.get(4));

            assertEquals(4, state.lastAppliedEventSequence());
            assertEquals(
                recording.positionAfterEventIndex(events.size() - 1),
                state.lastAppliedAeronPosition());
            assertEquals(4, state.appliedEventsTotal());
            assertEquals(1, state.duplicatesTotal());
            assertEquals(expectedDigestWithoutDuplicate, state.replayDigest());
            assertNotEquals(recording.expectedReplayDigest(), state.replayDigest());
            assertNotEquals(positionAfterFirstSequenceThree, positionAfterDuplicate);
            assertEquals(
                recording.positionAfterEventIndex(events.size() - 1),
                checkpoints.find(CHECKPOINT_KEY).orElseThrow().lastAppliedAeronPosition());
        }
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
            1_000_000 + sequence,
            10_000 + sequence,
            0,
            1,
            sequence % 2 == 0 ? Side.BUY : Side.SELL,
            100_000 + sequence,
            10 + sequence,
            10 + sequence);
    }
}
