package io.github.mikuwwl.matchingreplay.aeron;

import java.time.Instant;

public record ReplayProgress(
    long replayStartPosition,
    long currentPosition,
    long replayStopPosition,
    double progressPercent,
    long lastEventSequence,
    long appliedEventsThisRun,
    long duplicatesThisRun,
    long lastCheckpointPosition,
    long eventsPerSecond,
    Instant lastProgressAt)
{
    public ReplayProgress
    {
        if (replayStartPosition < 0 ||
            currentPosition < replayStartPosition ||
            replayStopPosition < replayStartPosition ||
            currentPosition > replayStopPosition ||
            progressPercent < 0 || progressPercent > 100 ||
            lastEventSequence < 0 ||
            appliedEventsThisRun < 0 ||
            duplicatesThisRun < 0 ||
            lastCheckpointPosition < replayStartPosition ||
            lastCheckpointPosition > currentPosition ||
            eventsPerSecond < 0 ||
            lastProgressAt == null)
        {
            throw new IllegalArgumentException("Invalid replay progress snapshot");
        }
    }

    public static ReplayProgress snapshot(
        final long replayStartPosition,
        final long currentPosition,
        final long replayStopPosition,
        final long lastEventSequence,
        final long appliedEventsThisRun,
        final long duplicatesThisRun,
        final long lastCheckpointPosition,
        final long eventsPerSecond,
        final Instant lastProgressAt)
    {
        final double percent;
        if (replayStopPosition == replayStartPosition)
        {
            percent = 100.0;
        }
        else
        {
            final double raw =
                100.0 * (currentPosition - replayStartPosition) /
                    (replayStopPosition - replayStartPosition);
            percent = Math.max(0.0, Math.min(100.0, raw));
        }
        return new ReplayProgress(
            replayStartPosition,
            currentPosition,
            replayStopPosition,
            percent,
            lastEventSequence,
            appliedEventsThisRun,
            duplicatesThisRun,
            lastCheckpointPosition,
            eventsPerSecond,
            lastProgressAt);
    }
}
