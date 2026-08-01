package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.Aeron;
import io.aeron.archive.client.AeronArchive;

import java.time.Duration;

public final class AeronClientFactory
{
    private AeronClientFactory()
    {
    }

    public static Aeron connectAeron(final RuntimePaths paths, final String clientName)
    {
        return Aeron.connect(
            new Aeron.Context()
                .aeronDirectoryName(paths.aeronDir().toString())
                .clientName(clientName)
                .errorHandler(ex ->
                {
                    System.err.println("AERON_CLIENT_ERROR client=" + clientName + " error=" + ex);
                    ex.printStackTrace(System.err);
                }));
    }

    public static AeronArchive connectArchive(
        final Aeron aeron,
        final String clientName,
        final Duration timeout)
    {
        return AeronArchive.connect(
            new AeronArchive.Context()
                .aeron(aeron)
                .ownsAeronClient(false)
                .clientName(clientName)
                .controlRequestChannel(AeronChannels.ARCHIVE_LOCAL_CONTROL_CHANNEL)
                .controlRequestStreamId(AeronChannels.ARCHIVE_LOCAL_CONTROL_STREAM_ID)
                .controlResponseChannel(AeronChannels.ARCHIVE_CONTROL_RESPONSE_CHANNEL)
                .messageTimeoutNs(timeout.toNanos())
                .errorHandler(ex ->
                {
                    System.err.println("AERON_ARCHIVE_CLIENT_ERROR client=" + clientName + " error=" + ex);
                    ex.printStackTrace(System.err);
                }));
    }
}
