package io.github.mikuwwl.matchingreplay.observability;

import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;
import io.github.mikuwwl.matchingreplay.aeron.ReplayProgress;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayMetricsTest
{
    @Test
    void exposesLowCardinalityReplayMetrics()
    {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final ReplayMetrics metrics = new ReplayMetrics(registry);
        final UUID jobId = UUID.randomUUID();
        final ReplayResult result = new ReplayResult(
            42,
            "orders",
            64,
            128,
            1,
            10,
            10,
            10,
            10,
            10,
            2,
            2,
            0,
            99,
            99,
            12,
            true);

        metrics.checkpointWritten();
        metrics.checkpointWriteFailed();
        metrics.updatePositionLag(jobId, 64);
        metrics.recordTerminal(
            "verified",
            Duration.ofMillis(12),
            result,
            null,
            null);
        metrics.recordTerminal(
            "failed",
            Duration.ofMillis(4),
            null,
            ReplayProgress.snapshot(
                64,
                96,
                128,
                14,
                4,
                1,
                80,
                1_000,
                Instant.EPOCH),
            ReplayFailure.basic(
                ReplayFailureCode.SEQUENCE_GAP,
                "Sequence gap"));

        assertEquals(
            1.0,
            registry.get("replay.jobs").tag("status", "verified").counter().count());
        assertEquals(
            1.0,
            registry.get("replay.jobs").tag("status", "failed").counter().count());
        assertEquals(14.0, registry.get("replay.events.applied").counter().count());
        assertEquals(3.0, registry.get("replay.duplicates").counter().count());
        assertEquals(1.0, registry.get("replay.sequence.gaps").counter().count());
        assertEquals(1.0, registry.get("replay.checkpoint.writes").counter().count());
        assertEquals(
            1.0,
            registry.get("replay.checkpoint.write.failures").counter().count());
        assertEquals(64.0, registry.get("replay.position.lag").gauge().value());

        metrics.clearPositionLag(jobId);
        assertEquals(0.0, registry.get("replay.position.lag").gauge().value());
    }
}
