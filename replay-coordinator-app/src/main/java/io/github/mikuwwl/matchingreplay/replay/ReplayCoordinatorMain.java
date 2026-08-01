package io.github.mikuwwl.matchingreplay.replay;

import io.github.mikuwwl.matchingreplay.aeron.Arguments;
import io.github.mikuwwl.matchingreplay.aeron.ReplayCoordinator;
import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;
import io.github.mikuwwl.matchingreplay.aeron.RuntimePaths;

import java.time.Duration;

public final class ReplayCoordinatorMain
{
    private ReplayCoordinatorMain()
    {
    }

    public static void main(final String[] args)
    {
        final Arguments arguments = Arguments.parse(args);
        final String consumerName = arguments.stringValue("consumerName", "asset-projection");
        final boolean followLive = arguments.booleanValue("followLive", false);
        if (followLive)
        {
            throw new IllegalArgumentException("Live ReplayMerge is outside this bounded-replay MVP");
        }
        final int timeoutSeconds = arguments.intValue("timeoutSeconds", 60);

        final ReplayResult result = new ReplayCoordinator(
            RuntimePaths.resolve().createDirectories(),
            consumerName,
            Duration.ofSeconds(timeoutSeconds)).replay();

        System.out.println("REPLAY_COMPLETED");
        System.out.println("recordingId=" + result.recordingId());
        System.out.println("replayStartPosition=" + result.replayStartPosition());
        System.out.println("replayStopPosition=" + result.replayStopPosition());
        System.out.println("firstRecoveredSequence=" + result.firstRecoveredSequence());
        System.out.println("lastRecoveredSequence=" + result.lastRecoveredSequence());
        System.out.println("finalSequence=" + result.finalSequence());
        System.out.println("gaps=" + result.gaps());
        System.out.println("duplicates=" + result.duplicates());
        System.out.println("stateHash=" + Long.toUnsignedString(result.stateHash()));
        System.out.println("replayDurationMs=" + result.replayDurationMs());
        System.out.println("status=" + (result.passed() ? "PASS" : "FAIL"));
        if (!result.passed())
        {
            throw new IllegalStateException("Replay verification failed");
        }
    }
}
