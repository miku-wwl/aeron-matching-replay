package io.github.mikuwwl.matchingreplay.projection;

import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectionStateTest
{
    @Test
    void duplicateDoesNotChangeReplayDigest()
    {
        final ProjectionState state = ProjectionState.from(
            Checkpoint.initial("orders", 7, 64));
        final MatchingEvent event = event(1);

        assertEquals(ProjectionState.ApplyResult.APPLIED, state.apply(event, 128));
        final long replayDigestAfterFirstApplication = state.replayDigest();

        assertEquals(ProjectionState.ApplyResult.DUPLICATE, state.apply(event, 192));
        assertEquals(1, state.lastAppliedEventSequence());
        assertEquals(192, state.lastAppliedAeronPosition());
        assertEquals(1, state.appliedEventsTotal());
        assertEquals(1, state.duplicatesTotal());
        assertEquals(0, state.sequenceGapsThisRun());
        assertEquals(replayDigestAfterFirstApplication, state.replayDigest());
    }

    private static MatchingEvent event(final long sequence)
    {
        return new MatchingEvent(
            (short)1,
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
            10 + sequence);
    }
}
