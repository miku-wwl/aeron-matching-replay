package io.github.mikuwwl.matchingreplay.aeron;

public record ReplayCommand(
    long recordingId,
    String checkpointKey,
    Long stopPosition,
    long expectedLastEventSequence,
    long expectedReplayDigest,
    String correlationId)
{
    public ReplayCommand
    {
        if (recordingId < 0 || checkpointKey == null || checkpointKey.isBlank())
        {
            throw new IllegalArgumentException("recordingId and checkpointKey are required");
        }
        if (stopPosition != null && stopPosition < 0)
        {
            throw new IllegalArgumentException("stopPosition must not be negative");
        }
        if (expectedLastEventSequence < 0)
        {
            throw new IllegalArgumentException("expectedLastEventSequence must not be negative");
        }
        correlationId = correlationId == null || correlationId.isBlank() ? null : correlationId;
    }
}
