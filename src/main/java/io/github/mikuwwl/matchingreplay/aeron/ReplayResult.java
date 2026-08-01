package io.github.mikuwwl.matchingreplay.aeron;

public record ReplayResult(
    long recordingId,
    String checkpointKey,
    long replayStartPosition,
    long replayStopPosition,
    long firstRecoveredSequence,
    long lastRecoveredSequence,
    long finalSequence,
    long appliedEvents,
    long gaps,
    long duplicates,
    long stateHash,
    long replayDurationMs,
    boolean verificationPassed)
{
}
