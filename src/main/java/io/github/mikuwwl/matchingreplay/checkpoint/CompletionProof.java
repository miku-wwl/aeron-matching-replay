package io.github.mikuwwl.matchingreplay.checkpoint;

import java.time.Instant;
import java.util.Properties;

public record CompletionProof(
    String checkpointKey,
    long recordingId,
    long replayStartPosition,
    long replayStopPosition,
    long finalEventSequence,
    long finalReplayDigest,
    CompletionVerificationStatus verificationStatus,
    Instant completedAt)
{
    public CompletionProof
    {
        if (checkpointKey == null || checkpointKey.isBlank() ||
            recordingId < 0 || replayStartPosition < 0 ||
            replayStopPosition < replayStartPosition ||
            finalEventSequence < 0 || verificationStatus == null ||
            completedAt == null)
        {
            throw new IllegalArgumentException("Invalid replay completion proof");
        }
    }

    Properties toProperties()
    {
        final Properties properties = new Properties();
        properties.setProperty("checkpointKey", checkpointKey);
        properties.setProperty("recordingId", Long.toString(recordingId));
        properties.setProperty("replayStartPosition", Long.toString(replayStartPosition));
        properties.setProperty("replayStopPosition", Long.toString(replayStopPosition));
        properties.setProperty("finalEventSequence", Long.toString(finalEventSequence));
        properties.setProperty("finalReplayDigest", Long.toUnsignedString(finalReplayDigest));
        properties.setProperty("verificationStatus", verificationStatus.name());
        properties.setProperty("completedAt", completedAt.toString());
        return properties;
    }

    static CompletionProof fromProperties(final Properties properties)
    {
        try
        {
            return new CompletionProof(
                AtomicPropertiesFile.require(properties, "checkpointKey"),
                Long.parseLong(AtomicPropertiesFile.require(properties, "recordingId")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "replayStartPosition")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "replayStopPosition")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "finalEventSequence")),
                Long.parseUnsignedLong(
                    AtomicPropertiesFile.require(properties, "finalReplayDigest")),
                CompletionVerificationStatus.valueOf(
                    AtomicPropertiesFile.require(properties, "verificationStatus")),
                Instant.parse(AtomicPropertiesFile.require(properties, "completedAt")));
        }
        catch (final RuntimeException ex)
        {
            throw new IllegalStateException("Corrupt replay completion proof", ex);
        }
    }
}
