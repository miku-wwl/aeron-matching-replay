package io.github.mikuwwl.matchingreplay.checkpoint;

import io.github.mikuwwl.matchingreplay.domain.ReplayDigest;

import java.time.Instant;
import java.util.Properties;

public record Checkpoint(
    String checkpointKey,
    long recordingId,
    long lastAppliedEventSequence,
    long lastAppliedAeronPosition,
    long appliedEventsTotal,
    long duplicatesTotal,
    long replayDigest,
    Instant updatedAt)
{
    public Checkpoint
    {
        if (checkpointKey == null || checkpointKey.isBlank() || recordingId < 0 ||
            lastAppliedEventSequence < 0 || lastAppliedAeronPosition < 0 ||
            appliedEventsTotal < 0 || duplicatesTotal < 0 ||
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
            ReplayDigest.INITIAL_VALUE,
            Instant.now());
    }

    Properties toProperties()
    {
        final Properties properties = new Properties();
        properties.setProperty("checkpointKey", checkpointKey);
        properties.setProperty("recordingId", Long.toString(recordingId));
        properties.setProperty("lastAppliedEventSequence", Long.toString(lastAppliedEventSequence));
        properties.setProperty("lastAppliedAeronPosition", Long.toString(lastAppliedAeronPosition));
        properties.setProperty("appliedEventsTotal", Long.toString(appliedEventsTotal));
        properties.setProperty("duplicatesTotal", Long.toString(duplicatesTotal));
        properties.setProperty("replayDigest", Long.toUnsignedString(replayDigest));
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
                Long.parseLong(requireCurrentOrLegacy(
                    properties,
                    "appliedEventsTotal",
                    "appliedEventCount")),
                Long.parseLong(requireCurrentOrLegacy(
                    properties,
                    "duplicatesTotal",
                    "duplicateEventCount")),
                Long.parseUnsignedLong(requireCurrentOrLegacy(
                    properties,
                    "replayDigest",
                    "stateHash")),
                Instant.parse(AtomicPropertiesFile.require(properties, "updatedAt")));
        }
        catch (final RuntimeException ex)
        {
            throw new IllegalStateException("Corrupt replay checkpoint", ex);
        }
    }

    private static String requireCurrentOrLegacy(
        final Properties properties,
        final String currentKey,
        final String legacyKey)
    {
        final String currentValue = properties.getProperty(currentKey);
        return currentValue == null || currentValue.isBlank() ?
            AtomicPropertiesFile.require(properties, legacyKey) : currentValue;
    }
}
