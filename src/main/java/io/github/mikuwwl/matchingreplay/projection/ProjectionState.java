package io.github.mikuwwl.matchingreplay.projection;

import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.ReplayDigest;
import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;

import java.time.Instant;

public final class ProjectionState
{
    private long lastAppliedEventSequence;
    private long lastAppliedAeronPosition;
    private long appliedEventsTotal;
    private long duplicatesTotal;
    private long sequenceGapsThisRun;
    private long replayDigest;

    private ProjectionState(final Checkpoint checkpoint)
    {
        lastAppliedEventSequence = checkpoint.lastAppliedEventSequence();
        lastAppliedAeronPosition = checkpoint.lastAppliedAeronPosition();
        appliedEventsTotal = checkpoint.appliedEventsTotal();
        duplicatesTotal = checkpoint.duplicatesTotal();
        replayDigest = checkpoint.replayDigest();
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
            ReplayDigest.INITIAL_VALUE,
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
            duplicatesTotal++;
            lastAppliedAeronPosition = aeronPosition;
            return ApplyResult.DUPLICATE;
        }
        if (event.eventSequence() != lastAppliedEventSequence + 1)
        {
            sequenceGapsThisRun++;
            throw new ReplayException(ReplayFailure.sequenceGap(
                lastAppliedAeronPosition,
                lastAppliedEventSequence,
                event.eventSequence()));
        }

        replayDigest = ReplayDigest.mixEvent(replayDigest, event);
        lastAppliedEventSequence = event.eventSequence();
        lastAppliedAeronPosition = aeronPosition;
        appliedEventsTotal++;
        return ApplyResult.APPLIED;
    }

    public Checkpoint checkpoint(final String checkpointKey, final long recordingId)
    {
        return new Checkpoint(
            checkpointKey,
            recordingId,
            lastAppliedEventSequence,
            lastAppliedAeronPosition,
            appliedEventsTotal,
            duplicatesTotal,
            replayDigest,
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

    public long appliedEventsTotal()
    {
        return appliedEventsTotal;
    }

    public long duplicatesTotal()
    {
        return duplicatesTotal;
    }

    public long sequenceGapsThisRun()
    {
        return sequenceGapsThisRun;
    }

    public long replayDigest()
    {
        return replayDigest;
    }

    public enum ApplyResult
    {
        APPLIED,
        DUPLICATE
    }
}
