package io.github.mikuwwl.matchingreplay.observability;

import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;
import io.github.mikuwwl.matchingreplay.aeron.ReplayProgress;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ReplayMetrics
{
    private final MeterRegistry registry;
    private final Counter eventsApplied;
    private final Counter duplicates;
    private final Counter sequenceGaps;
    private final Counter checkpointWrites;
    private final Counter checkpointWriteFailures;
    private final Counter noProgressTimeouts;
    private final Timer replayDuration;
    private final ConcurrentMap<UUID, Long> activePositionLag = new ConcurrentHashMap<>();

    public ReplayMetrics(final MeterRegistry registry)
    {
        this.registry = registry;
        eventsApplied = Counter.builder("replay.events.applied")
            .description("Events newly applied by replay jobs")
            .register(registry);
        duplicates = Counter.builder("replay.duplicates")
            .description("Duplicate messages suppressed by replay jobs")
            .register(registry);
        sequenceGaps = Counter.builder("replay.sequence.gaps")
            .description("Sequence gaps detected by replay jobs")
            .register(registry);
        checkpointWrites = Counter.builder("replay.checkpoint.writes")
            .description("Successfully persisted progress checkpoints")
            .register(registry);
        checkpointWriteFailures = Counter.builder("replay.checkpoint.write.failures")
            .description("Failed progress checkpoint writes")
            .register(registry);
        noProgressTimeouts = Counter.builder("replay.no.progress.timeouts")
            .description("Replay jobs failed because position stopped advancing")
            .register(registry);
        replayDuration = Timer.builder("replay.duration")
            .description("Replay execution duration")
            .register(registry);
        Gauge.builder(
                "replay.position.lag",
                activePositionLag,
                values -> values.values().stream().mapToLong(Long::longValue).sum())
            .description("Aggregate remaining Aeron Position across active replay jobs")
            .register(registry);
    }

    public static ReplayMetrics noop()
    {
        return new ReplayMetrics(new SimpleMeterRegistry());
    }

    public void checkpointWritten()
    {
        checkpointWrites.increment();
    }

    public void checkpointWriteFailed()
    {
        checkpointWriteFailures.increment();
    }

    public void updatePositionLag(final UUID jobId, final long positionLag)
    {
        activePositionLag.put(jobId, Math.max(0, positionLag));
    }

    public void clearPositionLag(final UUID jobId)
    {
        activePositionLag.remove(jobId);
    }

    public void recordTerminal(
        final String status,
        final Duration duration,
        final ReplayResult result,
        final ReplayProgress progress,
        final ReplayFailure failure)
    {
        Counter.builder("replay.jobs")
            .description("Replay jobs by terminal status")
            .tag("status", status)
            .register(registry)
            .increment();
        replayDuration.record(duration);
        if (result != null)
        {
            eventsApplied.increment(result.appliedEventsThisRun());
            duplicates.increment(result.duplicatesThisRun());
        }
        else if (progress != null)
        {
            eventsApplied.increment(progress.appliedEventsThisRun());
            duplicates.increment(progress.duplicatesThisRun());
        }
        if (failure != null && failure.code() == ReplayFailureCode.SEQUENCE_GAP)
        {
            sequenceGaps.increment();
        }
        if (failure != null && failure.code() == ReplayFailureCode.NO_PROGRESS_TIMEOUT)
        {
            noProgressTimeouts.increment();
        }
    }
}
