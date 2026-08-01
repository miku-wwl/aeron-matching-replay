package io.github.mikuwwl.matchingreplay.projection;

import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
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
    private long stateHash;

    private ProjectionState(final Checkpoint checkpoint)
    {
        lastAppliedEventSequence = checkpoint.lastAppliedEventSequence();
        lastAppliedAeronPosition = checkpoint.lastAppliedAeronPosition();
        appliedEventCount = checkpoint.appliedEventCount();
        duplicateEventCount = checkpoint.duplicateEventCount();
        gapCount = checkpoint.gapCount();
        stateHash = checkpoint.stateHash();
    }

    public static ProjectionState from(final Checkpoint checkpoint)
    {
        return new ProjectionState(checkpoint);
    }

    public static ProjectionState initial(final long recordingStartPosition)
    {
        return new ProjectionState(new Checkpoint(
            "initial",
            0,
            0,
            recordingStartPosition,
            0,
            0,
            0,
            Hashing.FNV_OFFSET_BASIS,
            Instant.EPOCH));
    }

    public ApplyResult apply(final MatchingEvent event, final long aeronPosition)
    {
        if (aeronPosition < lastAppliedAeronPosition)
        {
            throw new IllegalArgumentException(
                "Aeron position moved backwards: previous=" + lastAppliedAeronPosition +
                ", actual=" + aeronPosition);
        }
        if (event.eventSequence() <= lastAppliedEventSequence)
        {
            duplicateEventCount++;
            lastAppliedAeronPosition = aeronPosition;
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

    public Checkpoint checkpoint(final String checkpointKey, final long recordingId)
    {
        return new Checkpoint(
            checkpointKey,
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
