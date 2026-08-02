package io.github.mikuwwl.matchingreplay.aeron;

import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoProgressWatchdogTest
{
    @Test
    void continuouslyAdvancingReplayCanRunLongerThanNoProgressTimeout()
    {
        final AtomicLong nanoClock = new AtomicLong();
        final NoProgressWatchdog watchdog = new NoProgressWatchdog(
            Duration.ofMillis(100),
            null,
            0,
            nanoClock::get);

        for (long position = 1; position <= 20; position++)
        {
            nanoClock.addAndGet(Duration.ofMillis(90).toNanos());
            final long observedPosition = position;
            assertDoesNotThrow(() -> watchdog.check(
                42,
                observedPosition,
                100,
                observedPosition));
        }
    }

    @Test
    void stalledReplayFailsWithStructuredNoProgressDiagnostics()
    {
        final AtomicLong nanoClock = new AtomicLong();
        final NoProgressWatchdog watchdog = new NoProgressWatchdog(
            Duration.ofMillis(100),
            null,
            64,
            nanoClock::get);

        nanoClock.addAndGet(Duration.ofMillis(101).toNanos());
        final ReplayException exception = assertThrows(
            ReplayException.class,
            () -> watchdog.check(42, 64, 1_024, 400));

        assertEquals(ReplayFailureCode.NO_PROGRESS_TIMEOUT, exception.failure().code());
        assertEquals(42, exception.failure().recordingId());
        assertEquals(64, exception.failure().currentPosition());
        assertEquals(1_024, exception.failure().replayStopPosition());
        assertEquals(400, exception.failure().lastAppliedEventSequence());
        assertEquals(101, exception.failure().noProgressMillis());
    }
}
