package io.github.mikuwwl.matchingreplay.application;

import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.aeron.ReplayEngine;
import io.github.mikuwwl.matchingreplay.aeron.ReplayProgress;
import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;
import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayJobManagerTest
{
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void completesJobAndKeepsItsResult()
    {
        final ReplayCommand command = command("orders");
        final ReplayResult result = successfulResult("orders");
        final ReplayEngine replayEngine = (ignored, listener) -> result;
        final ReplayJobManager manager =
            new ReplayJobManager(replayEngine, Runnable::run, CLOCK);

        final ReplayJobSnapshot snapshot = manager.start(command);

        assertEquals(ReplayJobState.VERIFIED, snapshot.state());
        assertEquals(10, snapshot.result().finalEventSequence());
        assertEquals(snapshot, manager.find(snapshot.jobId()).orElseThrow());
    }

    @Test
    void exposesChangingProgressWhileJobIsRunning() throws Exception
    {
        final CountDownLatch progressPublished = new CountDownLatch(1);
        final CountDownLatch releaseReplay = new CountDownLatch(1);
        final ReplayProgress progress = ReplayProgress.snapshot(
            64,
            96,
            128,
            5,
            5,
            0,
            80,
            1_200,
            Instant.parse("2026-08-02T00:00:01Z"));
        final ReplayEngine replayEngine = (command, listener) ->
        {
            listener.onProgress(progress);
            progressPublished.countDown();
            await(releaseReplay);
            return successfulResult(command.checkpointKey());
        };
        final TaskExecutor asynchronous =
            task -> Thread.startVirtualThread(task);
        final ReplayJobManager manager =
            new ReplayJobManager(replayEngine, asynchronous, CLOCK);

        final ReplayJobSnapshot accepted = manager.start(command("live-progress"));
        assertTrue(progressPublished.await(5, TimeUnit.SECONDS));

        final ReplayJobSnapshot running = manager.find(accepted.jobId()).orElseThrow();
        assertEquals(ReplayJobState.RUNNING, running.state());
        assertNotNull(running.progress());
        assertEquals(96, running.progress().currentPosition());
        assertEquals(50.0, running.progress().progressPercent());
        assertEquals(80, running.progress().lastCheckpointPosition());

        releaseReplay.countDown();
        awaitTerminal(manager, accepted);
    }

    @Test
    void rejectsConcurrentUseOfSameCheckpoint()
    {
        final ReplayEngine replayEngine = (command, listener) -> null;
        final TaskExecutor queuedExecutor = task -> { };
        final ReplayJobManager manager =
            new ReplayJobManager(replayEngine, queuedExecutor, CLOCK);

        manager.start(command("orders"));

        assertThrows(
            ReplayConflictException.class,
            () -> manager.start(command("orders")));
        assertEquals(1, manager.list().size());
    }

    @Test
    void mapsExpectedReplayFailureToStableCode()
    {
        final ReplayEngine replayEngine = (command, listener) ->
        {
            throw new ReplayException(ReplayFailure.noProgress(
                command.recordingId(),
                64,
                128,
                5,
                1_001));
        };
        final ReplayJobManager manager =
            new ReplayJobManager(replayEngine, Runnable::run, CLOCK);

        final ReplayJobSnapshot snapshot = manager.start(command("failure"));

        assertEquals(ReplayJobState.FAILED, snapshot.state());
        assertEquals(ReplayFailureCode.NO_PROGRESS_TIMEOUT, snapshot.failure().code());
        assertEquals(64, snapshot.failure().currentPosition());
        assertEquals(5, snapshot.failure().lastAppliedEventSequence());
    }

    private static ReplayCommand command(final String checkpointKey)
    {
        return new ReplayCommand(7, checkpointKey, 128L, 10L, 99L, "test");
    }

    private static ReplayResult successfulResult(final String checkpointKey)
    {
        return new ReplayResult(
            7,
            checkpointKey,
            0,
            128,
            1,
            10,
            10,
            10,
            10,
            10,
            0,
            0,
            0,
            99,
            99,
            12,
            true);
    }

    private static void await(final CountDownLatch latch)
    {
        try
        {
            if (!latch.await(5, TimeUnit.SECONDS))
            {
                throw new AssertionError("Timed out waiting for test latch");
            }
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static void awaitTerminal(
        final ReplayJobManager manager,
        final ReplayJobSnapshot accepted)
        throws Exception
    {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (manager.find(accepted.jobId()).orElseThrow().state() == ReplayJobState.RUNNING)
        {
            if (System.nanoTime() >= deadline)
            {
                throw new AssertionError("Replay job did not reach a terminal state");
            }
            Thread.sleep(10);
        }
        assertEquals(
            ReplayJobState.VERIFIED,
            manager.find(accepted.jobId()).orElseThrow().state());
    }
}
