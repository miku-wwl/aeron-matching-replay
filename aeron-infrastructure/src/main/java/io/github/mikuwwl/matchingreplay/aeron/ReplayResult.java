package io.github.mikuwwl.matchingreplay.aeron;

public record ReplayResult(
    long recordingId,
    long replayStartPosition,
    long replayStopPosition,
    long firstRecoveredSequence,
    long lastRecoveredSequence,
    long finalSequence,
    long gaps,
    long duplicates,
    long stateHash,
    long replayDurationMs,
    boolean passed)
{
}
