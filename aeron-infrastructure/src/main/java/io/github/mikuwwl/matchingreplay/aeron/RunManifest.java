package io.github.mikuwwl.matchingreplay.aeron;

import java.time.Instant;
import java.util.Properties;

public record RunManifest(
    String runId,
    long recordingId,
    int publicationSessionId,
    String channel,
    int liveStreamId,
    long firstEventSequence,
    long lastEventSequence,
    long eventsPublished,
    long lastPublishedPosition,
    long recordedPosition,
    long expectedProjectionHash,
    long orderBookHash,
    long seed,
    int orderCount,
    Instant updatedAt)
{
    public RunManifest
    {
        if (runId == null || runId.isBlank() || recordingId < 0 || channel == null || channel.isBlank() ||
            liveStreamId <= 0 || firstEventSequence <= 0 || lastEventSequence < 0 ||
            eventsPublished < 0 || lastPublishedPosition < 0 || recordedPosition < 0 ||
            orderCount <= 0 || updatedAt == null)
        {
            throw new IllegalArgumentException("Invalid run manifest");
        }
    }

    public static RunManifest initial(
        final String runId,
        final long recordingId,
        final int publicationSessionId,
        final long seed,
        final int orderCount)
    {
        return new RunManifest(
            runId,
            recordingId,
            publicationSessionId,
            AeronChannels.LIVE_CHANNEL,
            AeronChannels.LIVE_STREAM_ID,
            1,
            0,
            0,
            0,
            0,
            io.github.mikuwwl.matchingreplay.domain.Hashing.FNV_OFFSET_BASIS,
            0,
            seed,
            orderCount,
            Instant.now());
    }

    Properties toProperties()
    {
        final Properties properties = new Properties();
        properties.setProperty("runId", runId);
        properties.setProperty("recordingId", Long.toString(recordingId));
        properties.setProperty("publicationSessionId", Integer.toString(publicationSessionId));
        properties.setProperty("channel", channel);
        properties.setProperty("liveStreamId", Integer.toString(liveStreamId));
        properties.setProperty("firstEventSequence", Long.toString(firstEventSequence));
        properties.setProperty("lastEventSequence", Long.toString(lastEventSequence));
        properties.setProperty("eventsPublished", Long.toString(eventsPublished));
        properties.setProperty("lastPublishedPosition", Long.toString(lastPublishedPosition));
        properties.setProperty("recordedPosition", Long.toString(recordedPosition));
        properties.setProperty("expectedProjectionHash", Long.toUnsignedString(expectedProjectionHash));
        properties.setProperty("orderBookHash", Long.toUnsignedString(orderBookHash));
        properties.setProperty("seed", Long.toString(seed));
        properties.setProperty("orderCount", Integer.toString(orderCount));
        properties.setProperty("updatedAt", updatedAt.toString());
        return properties;
    }

    static RunManifest fromProperties(final Properties properties)
    {
        try
        {
            return new RunManifest(
                AtomicPropertiesFile.require(properties, "runId"),
                Long.parseLong(AtomicPropertiesFile.require(properties, "recordingId")),
                Integer.parseInt(AtomicPropertiesFile.require(properties, "publicationSessionId")),
                AtomicPropertiesFile.require(properties, "channel"),
                Integer.parseInt(AtomicPropertiesFile.require(properties, "liveStreamId")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "firstEventSequence")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "lastEventSequence")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "eventsPublished")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "lastPublishedPosition")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "recordedPosition")),
                Long.parseUnsignedLong(AtomicPropertiesFile.require(properties, "expectedProjectionHash")),
                Long.parseUnsignedLong(AtomicPropertiesFile.require(properties, "orderBookHash")),
                Long.parseLong(AtomicPropertiesFile.require(properties, "seed")),
                Integer.parseInt(AtomicPropertiesFile.require(properties, "orderCount")),
                Instant.parse(AtomicPropertiesFile.require(properties, "updatedAt")));
        }
        catch (final RuntimeException ex)
        {
            throw new IllegalStateException("Corrupt run manifest", ex);
        }
    }
}
