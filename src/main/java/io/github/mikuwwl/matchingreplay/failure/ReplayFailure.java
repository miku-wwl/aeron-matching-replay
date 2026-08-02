package io.github.mikuwwl.matchingreplay.failure;

public record ReplayFailure(
    ReplayFailureCode code,
    String message,
    Long recordingId,
    Long currentPosition,
    Long replayStopPosition,
    Long lastAppliedEventSequence,
    Long receivedEventSequence,
    Integer templateId,
    Integer schemaId,
    Integer actingVersion,
    Integer actingBlockLength,
    Integer minimumSupportedBlockLength,
    Long fragmentPosition,
    Long expectedLastEventSequence,
    Long actualLastEventSequence,
    String expectedReplayDigest,
    String actualReplayDigest,
    Long timeSinceLastProgressMillis,
    Long configuredNoProgressTimeoutMillis)
{
    public ReplayFailure
    {
        if (code == null || message == null || message.isBlank())
        {
            throw new IllegalArgumentException("Replay failure code and message are required");
        }
    }

    public static ReplayFailure basic(
        final ReplayFailureCode code,
        final String message)
    {
        return new ReplayFailure(
            code,
            message,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }

    public static ReplayFailure sequenceGap(
        final long currentPosition,
        final long lastAppliedEventSequence,
        final long receivedEventSequence)
    {
        return new ReplayFailure(
            ReplayFailureCode.SEQUENCE_GAP,
            "Expected sequence " + (lastAppliedEventSequence + 1) +
                " but received " + receivedEventSequence,
            null,
            currentPosition,
            null,
            lastAppliedEventSequence,
            receivedEventSequence,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }

    public static ReplayFailure noProgress(
        final long recordingId,
        final long currentPosition,
        final long replayStopPosition,
        final long lastAppliedEventSequence,
        final long timeSinceLastProgressMillis,
        final long configuredNoProgressTimeoutMillis)
    {
        return new ReplayFailure(
            ReplayFailureCode.NO_PROGRESS_TIMEOUT,
            "Replay made no progress for " + timeSinceLastProgressMillis +
                " ms at position=" + currentPosition +
                ", stopPosition=" + replayStopPosition +
                ", lastEventSequence=" + lastAppliedEventSequence +
                ", configuredTimeout=" + configuredNoProgressTimeoutMillis + " ms",
            recordingId,
            currentPosition,
            replayStopPosition,
            lastAppliedEventSequence,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            timeSinceLastProgressMillis,
            configuredNoProgressTimeoutMillis);
    }

    public static ReplayFailure maximumReplayDuration(
        final long recordingId,
        final long currentPosition,
        final long replayStopPosition,
        final long lastAppliedEventSequence,
        final long durationMillis)
    {
        return new ReplayFailure(
            ReplayFailureCode.MAXIMUM_REPLAY_DURATION,
            "Replay exceeded maximum duration of " + durationMillis +
                " ms at position=" + currentPosition +
                ", stopPosition=" + replayStopPosition,
            recordingId,
            currentPosition,
            replayStopPosition,
            lastAppliedEventSequence,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }

    public static ReplayFailure sbe(
        final ReplayFailureCode code,
        final String message,
        final Integer templateId,
        final Integer schemaId,
        final Integer actingVersion)
    {
        return sbe(
            code,
            message,
            templateId,
            schemaId,
            actingVersion,
            null,
            null);
    }

    public static ReplayFailure sbe(
        final ReplayFailureCode code,
        final String message,
        final Integer templateId,
        final Integer schemaId,
        final Integer actingVersion,
        final Integer actingBlockLength,
        final Integer minimumSupportedBlockLength)
    {
        return new ReplayFailure(
            code,
            message,
            null,
            null,
            null,
            null,
            null,
            templateId,
            schemaId,
            actingVersion,
            actingBlockLength,
            minimumSupportedBlockLength,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }

    public static ReplayFailure verificationMismatch(
        final long recordingId,
        final long currentPosition,
        final long replayStopPosition,
        final long expectedLastEventSequence,
        final long actualLastEventSequence,
        final long expectedReplayDigest,
        final long actualReplayDigest)
    {
        return new ReplayFailure(
            ReplayFailureCode.VERIFICATION_MISMATCH,
            "Replay completed but final sequence or replay digest did not match",
            recordingId,
            currentPosition,
            replayStopPosition,
            actualLastEventSequence,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            expectedLastEventSequence,
            actualLastEventSequence,
            Long.toUnsignedString(expectedReplayDigest),
            Long.toUnsignedString(actualReplayDigest),
            null,
            null);
    }

    public ReplayFailure withReplayContext(
        final long fallbackRecordingId,
        final long fallbackCurrentPosition,
        final long fallbackReplayStopPosition,
        final long fallbackLastEventSequence)
    {
        return new ReplayFailure(
            code,
            message,
            recordingId == null ? fallbackRecordingId : recordingId,
            currentPosition == null ? fallbackCurrentPosition : currentPosition,
            replayStopPosition == null ? fallbackReplayStopPosition : replayStopPosition,
            lastAppliedEventSequence == null ?
                fallbackLastEventSequence : lastAppliedEventSequence,
            receivedEventSequence,
            templateId,
            schemaId,
            actingVersion,
            actingBlockLength,
            minimumSupportedBlockLength,
            fragmentPosition,
            expectedLastEventSequence,
            actualLastEventSequence,
            expectedReplayDigest,
            actualReplayDigest,
            timeSinceLastProgressMillis,
            configuredNoProgressTimeoutMillis);
    }

    public ReplayFailure withFragmentPosition(final long position)
    {
        return new ReplayFailure(
            code,
            message,
            recordingId,
            currentPosition,
            replayStopPosition,
            lastAppliedEventSequence,
            receivedEventSequence,
            templateId,
            schemaId,
            actingVersion,
            actingBlockLength,
            minimumSupportedBlockLength,
            fragmentPosition == null ? position : fragmentPosition,
            expectedLastEventSequence,
            actualLastEventSequence,
            expectedReplayDigest,
            actualReplayDigest,
            timeSinceLastProgressMillis,
            configuredNoProgressTimeoutMillis);
    }
}
