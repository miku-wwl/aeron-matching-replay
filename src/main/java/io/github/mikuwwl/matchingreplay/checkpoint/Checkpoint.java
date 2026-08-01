package io.github.mikuwwl.matchingreplay.checkpoint;

import io.github.mikuwwl.matchingreplay.domain.Hashing;

import java.time.Instant;
import java.util.Properties;

public record Checkpoint(
    String checkpointKey,
    long recordingId,
    long lastAppliedEventSequence,
    long lastAppliedAeronPosition,
    long appliedEventCount,
    long duplicateEventCount,
    long gapCount,
    long stateHash,
    Instant updatedAt)
{
    public Checkpoint
    {
        if (checkpointKey == null || checkpointKey.isBlank() || recordingId < 0 ||
            lastAppliedEventSequence < 0 || lastAppliedAeronPosition < 0 ||
            appliedEventCount < 0 || duplicateEventCount < 0 || gapCount < 0 ||
            updatedAt == null)
        {
            throw new IllegalArgumentException("Invalid replay checkpoint");
        }
    }

    public static Checkpoint initial(
        final String checkpointKey,
        final long recordingId,
        final long recordingStartPosition)
    {
        return new Checkpoint(
            checkpointKey,
            recordingId,
            0,
            recordingStartPosition,
            0,
            0,
            0,
            Hashing.FNV_OFFSET_BASIS,
            Instant.now());
    }

    Properties toProperties()
    {
        final Properties properties = new Properties();
        properties.setProperty("checkpointKey", checkpointKey);
        properties.setProperty("recordingId", Long.toString(recordingId));
        properties.setProperty("lastAppliedEventSequence", Long.toString(lastAppliedEventSequence));
        properties.setProperty("lastAppliedAeronPosition", Long.toString(lastAppliedAeronPosition));
        properties.setProperty("appliedEventCount", Long.toString(appliedEventCount));
        properties.setProperty("duplicateEventCount", Long.toString(duplicateEventCount));
        properties.setProperty("gapCount", Long.toString(gapCount));
        properties.setProperty("stateHash", Long.toUnsignedString(stateHash));
        properties.setProperty("updatedAt", updatedAt.toString());
        return properties;
    }

    static Checkpoint fromProperties(final Properties properties)
    {
        try
        {
            return new Checkpoint(
                AtomicPropertiesFile.require(properties, "checkpointKey"),
                Long.parseLong(AtomicPropertiesFile.require(properties, "recordingId")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "lastAppliedEventSequence")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "lastAppliedAeronPosition")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "appliedEventCount")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "duplicateEventCount")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "gapCount")),
                Long.parseUnsignedLong(AtomicPropertiesFile.require(properties, "stateHash")),
                Instant.parse(AtomicPropertiesFile.require(properties, "updatedAt")));
        }
        catch (final RuntimeException ex)
        {
            throw new IllegalStateException("Corrupt replay checkpoint", ex);
        }
    }
}
