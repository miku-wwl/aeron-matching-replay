package io.github.mikuwwl.matchingreplay.aeron;

import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;

import java.time.Duration;
final class NoProgressWatchdog
{
    private final long noProgressTimeoutNs;
    private final Long maximumReplayDurationNs;
    private final MonotonicClock clock;
    private final long startedNs;

    private long lastProgressNs;
    private long lastObservedPosition;

    NoProgressWatchdog(
        final Duration noProgressTimeout,
        final Duration maximumReplayDuration,
        final long initialPosition,
        final MonotonicClock clock)
    {
        if (noProgressTimeout == null || noProgressTimeout.isZero() ||
            noProgressTimeout.isNegative())
        {
            throw new IllegalArgumentException("noProgressTimeout must be positive");
        }
        this.noProgressTimeoutNs = noProgressTimeout.toNanos();
        this.maximumReplayDurationNs = maximumReplayDuration == null ?
            null : maximumReplayDuration.toNanos();
        this.clock = clock;
        startedNs = clock.nanoTime();
        lastProgressNs = startedNs;
        lastObservedPosition = initialPosition;
    }

    void check(
        final long recordingId,
        final long currentPosition,
        final long replayStopPosition,
        final long lastEventSequence)
    {
        final long nowNs = clock.nanoTime();
        if (currentPosition > lastObservedPosition)
        {
            lastObservedPosition = currentPosition;
            lastProgressNs = nowNs;
        }

        if (maximumReplayDurationNs != null &&
            nowNs - startedNs > maximumReplayDurationNs)
        {
            throw new ReplayException(ReplayFailure.maximumReplayDuration(
                recordingId,
                currentPosition,
                replayStopPosition,
                lastEventSequence,
                Duration.ofNanos(maximumReplayDurationNs).toMillis()));
        }

        final long noProgressNs = nowNs - lastProgressNs;
        if (noProgressNs > noProgressTimeoutNs)
        {
            throw new ReplayException(ReplayFailure.noProgress(
                recordingId,
                currentPosition,
                replayStopPosition,
                lastEventSequence,
                Duration.ofNanos(noProgressNs).toMillis(),
                Duration.ofNanos(noProgressTimeoutNs).toMillis()));
        }
    }
}
