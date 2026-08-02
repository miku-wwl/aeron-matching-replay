package io.github.mikuwwl.matchingreplay.aeron;

public record ReplayResult(
    long recordingId,
    String checkpointKey,
    long replayStartPosition,
    long replayStopPosition,
    long firstAppliedEventSequenceThisRun,
    long lastAppliedEventSequenceThisRun,
    long finalEventSequence,
    long expectedLastEventSequence,
    long appliedEventsThisRun,
    long appliedEventsTotal,
    long duplicatesThisRun,
    long duplicatesTotal,
    long sequenceGapsThisRun,
    long finalReplayDigest,
    long expectedReplayDigest,
    long replayDurationMs,
    boolean verificationPassed)
{
}
