package io.github.mikuwwl.matchingreplay.aeron;

import io.github.mikuwwl.matchingreplay.domain.Hashing;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;

import java.time.Instant;

public final class ProjectionState
{
    private long lastAppliedEventSequence;
    private long lastAppliedAeronPosition;
    private long appliedEventCount;
    private long duplicateEventCount;
    private long gapCount;
    private long stateHash = Hashing.FNV_OFFSET_BASIS;

    public static ProjectionState from(final Checkpoint checkpoint)
    {
        final ProjectionState state = new ProjectionState();
        state.lastAppliedEventSequence = checkpoint.lastAppliedEventSequence();
        state.lastAppliedAeronPosition = checkpoint.lastAppliedAeronPosition();
        state.appliedEventCount = checkpoint.appliedEventCount();
        state.duplicateEventCount = checkpoint.duplicateEventCount();
        state.gapCount = checkpoint.gapCount();
        state.stateHash = checkpoint.stateHash();
        return state;
    }

    public ApplyResult apply(final MatchingEvent event, final long aeronPosition)
    {
        if (aeronPosition < 0)
        {
            throw new IllegalArgumentException("Aeron position must not be negative");
        }

        if (event.eventSequence() <= lastAppliedEventSequence)
        {
            duplicateEventCount++;
            lastAppliedAeronPosition = Math.max(lastAppliedAeronPosition, aeronPosition);
            return ApplyResult.DUPLICATE;
        }
        if (event.eventSequence() != lastAppliedEventSequence + 1)
        {
            gapCount++;
            throw new IllegalStateException(
                "Event gap: expected=" + (lastAppliedEventSequence + 1) +
                ", actual=" + event.eventSequence());
        }

        stateHash = Hashing.mixEvent(stateHash, event);
        lastAppliedEventSequence = event.eventSequence();
        lastAppliedAeronPosition = aeronPosition;
        appliedEventCount++;
        return ApplyResult.APPLIED;
    }

    public Checkpoint checkpoint(final String consumerName, final long recordingId)
    {
        return new Checkpoint(
            consumerName,
            recordingId,
            lastAppliedEventSequence,
            lastAppliedAeronPosition,
            appliedEventCount,
            duplicateEventCount,
            gapCount,
            stateHash,
            Instant.now());
    }

    public long lastAppliedEventSequence()
    {
        return lastAppliedEventSequence;
    }

    public long lastAppliedAeronPosition()
    {
        return lastAppliedAeronPosition;
    }

    public long appliedEventCount()
    {
        return appliedEventCount;
    }

    public long duplicateEventCount()
    {
        return duplicateEventCount;
    }

    public long gapCount()
    {
        return gapCount;
    }

    public long stateHash()
    {
        return stateHash;
    }

    public enum ApplyResult
    {
        APPLIED,
        DUPLICATE
    }
}
