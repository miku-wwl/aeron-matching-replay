package io.github.mikuwwl.matchingreplay.checkpoint;

import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

public record CompletionProof(
    UUID jobId,
    UUID attemptId,
    String correlationId,
    String checkpointKey,
    long recordingId,
    long replayStartPosition,
    long replayStopPosition,
    long finalEventSequence,
    long finalReplayDigest,
    boolean resumedFromCheckpoint,
    CompletionVerificationStatus verificationStatus,
    Instant completedAt)
{
    public CompletionProof
    {
        if (jobId == null || attemptId == null ||
            checkpointKey == null || checkpointKey.isBlank() ||
            recordingId < 0 || replayStartPosition < 0 ||
            replayStopPosition < replayStartPosition ||
            finalEventSequence < 0 || verificationStatus == null ||
            completedAt == null)
        {
            throw new IllegalArgumentException("Invalid replay completion proof");
        }
        correlationId =
            correlationId == null || correlationId.isBlank() ? null : correlationId;
    }

    Properties toProperties()
    {
        final Properties properties = new Properties();
        properties.setProperty("jobId", jobId.toString());
        properties.setProperty("attemptId", attemptId.toString());
        properties.setProperty("correlationId", correlationId == null ? "" : correlationId);
        properties.setProperty("checkpointKey", checkpointKey);
        properties.setProperty("recordingId", Long.toString(recordingId));
        properties.setProperty("replayStartPosition", Long.toString(replayStartPosition));
        properties.setProperty("replayStopPosition", Long.toString(replayStopPosition));
        properties.setProperty("finalEventSequence", Long.toString(finalEventSequence));
        properties.setProperty("finalReplayDigest", Long.toUnsignedString(finalReplayDigest));
        properties.setProperty(
            "resumedFromCheckpoint",
            Boolean.toString(resumedFromCheckpoint));
        properties.setProperty("verificationStatus", verificationStatus.name());
        properties.setProperty("completedAt", completedAt.toString());
        return properties;
    }

    static CompletionProof fromProperties(final Properties properties)
    {
        try
        {
            final String correlationId = properties.getProperty("correlationId");
            return new CompletionProof(
                UUID.fromString(AtomicPropertiesFile.require(properties, "jobId")),
                UUID.fromString(AtomicPropertiesFile.require(properties, "attemptId")),
                correlationId == null || correlationId.isBlank() ? null : correlationId,
                AtomicPropertiesFile.require(properties, "checkpointKey"),
                Long.parseLong(AtomicPropertiesFile.require(properties, "recordingId")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "replayStartPosition")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "replayStopPosition")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "finalEventSequence")),
                Long.parseUnsignedLong(
                    AtomicPropertiesFile.require(properties, "finalReplayDigest")),
                Boolean.parseBoolean(
                    AtomicPropertiesFile.require(properties, "resumedFromCheckpoint")),
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
