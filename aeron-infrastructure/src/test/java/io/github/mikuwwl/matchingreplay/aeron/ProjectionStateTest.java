package io.github.mikuwwl.matchingreplay.aeron;

import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectionStateTest
{
    @Test
    void appliesContinuousEventsSuppressesDuplicatesAndDetectsGaps()
    {
        final ProjectionState state = new ProjectionState();
        assertEquals(ProjectionState.ApplyResult.APPLIED, state.apply(event(1), 64));
        final long hashAfterOne = state.stateHash();
        assertEquals(ProjectionState.ApplyResult.DUPLICATE, state.apply(event(1), 64));
        assertEquals(hashAfterOne, state.stateHash());
        assertEquals(1, state.duplicateEventCount());
        assertThrows(IllegalStateException.class, () -> state.apply(event(3), 192));
        assertEquals(1, state.gapCount());
    }

    @Test
    void stateHashIsDeterministicAcrossRestoration()
    {
        final ProjectionState uninterrupted = new ProjectionState();
        uninterrupted.apply(event(1), 64);
        uninterrupted.apply(event(2), 128);

        final ProjectionState partial = new ProjectionState();
        partial.apply(event(1), 64);
        final ProjectionState restored = ProjectionState.from(partial.checkpoint("consumer", 1));
        restored.apply(event(2), 128);

        assertEquals(uninterrupted.stateHash(), restored.stateHash());
        assertEquals(uninterrupted.lastAppliedEventSequence(), restored.lastAppliedEventSequence());
    }

    @Test
    void invalidReplayPositionsFailFast()
    {
        assertThrows(IllegalArgumentException.class, () -> ReplayCoordinator.validatePosition(9, 10, 20));
        assertThrows(IllegalArgumentException.class, () -> ReplayCoordinator.validatePosition(21, 10, 20));
        ReplayCoordinator.validatePosition(10, 10, 20);
        ReplayCoordinator.validatePosition(20, 10, 20);
    }

    private static MatchingEvent event(final long sequence)
    {
        return new MatchingEvent(
            (short)1,
            EventType.ORDER_ACCEPTED,
            sequence,
            sequence,
            sequence,
            0,
            0,
            1,
            Side.BUY,
            100,
            10,
            10);
    }
}
