package io.github.mikuwwl.matchingreplay.application;

import io.github.mikuwwl.matchingreplay.aeron.AeronReplayCoordinator;
import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayJobManagerTest
{
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void completesJobAndKeepsItsResult()
    {
        final ReplayCommand command = command("orders");
        final ReplayResult result = new ReplayResult(
            7,
            "orders",
            0,
            128,
            1,
            10,
            10,
            10,
            0,
            0,
            99,
            12,
            true);
        final AeronReplayCoordinator coordinator = coordinatorReturning(result);
        final ReplayJobManager manager =
            new ReplayJobManager(coordinator, Runnable::run, CLOCK);

        final ReplayJobSnapshot snapshot = manager.start(command);

        assertEquals(ReplayJobState.SUCCEEDED, snapshot.state());
        assertEquals(10, snapshot.result().finalSequence());
        assertEquals(snapshot, manager.find(snapshot.jobId()).orElseThrow());
    }

    @Test
    void rejectsConcurrentUseOfSameCheckpoint()
    {
        final AeronReplayCoordinator coordinator = coordinatorReturning(null);
        final TaskExecutor queuedExecutor = task -> { };
        final ReplayJobManager manager =
            new ReplayJobManager(coordinator, queuedExecutor, CLOCK);

        manager.start(command("orders"));

        assertThrows(
            ReplayConflictException.class,
            () -> manager.start(command("orders")));
        assertEquals(1, manager.list().size());
    }

    private static ReplayCommand command(final String checkpointKey)
    {
        return new ReplayCommand(7, checkpointKey, 128L, 10L, 99L, "test");
    }

    private static AeronReplayCoordinator coordinatorReturning(final ReplayResult result)
    {
        return new AeronReplayCoordinator(null, null, null)
        {
            @Override
            public ReplayResult replay(final ReplayCommand ignored)
            {
                return result;
            }
        };
    }
}
