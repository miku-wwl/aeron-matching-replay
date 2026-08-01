package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.Aeron;
import io.aeron.archive.client.AeronArchive;

import java.time.Duration;

public final class ListRecordingsMain
{
    private ListRecordingsMain()
    {
    }

    public static void main(final String[] args)
    {
        final RuntimePaths paths = RuntimePaths.resolve();
        System.out.println("archiveDirectory=" + paths.archiveDir());
        try (Aeron aeron = AeronClientFactory.connectAeron(paths, "archive-inspector");
            AeronArchive archive = AeronClientFactory.connectArchive(
                aeron,
                "archive-inspector-client",
                Duration.ofSeconds(10)))
        {
            final int count = archive.listRecordings(
                0,
                1_000,
                (controlSessionId,
                    correlationId,
                    recordingId,
                    startTimestamp,
                    stopTimestamp,
                    startPosition,
                    stopPosition,
                    initialTermId,
                    segmentFileLength,
                    termBufferLength,
                    mtuLength,
                    sessionId,
                    streamId,
                    strippedChannel,
                    originalChannel,
                    sourceIdentity) ->
                    System.out.printf(
                        "recordingId=%d streamId=%d sessionId=%d startPosition=%d stopPosition=%d " +
                        "activeRecordingPosition=%d channel=%s%n",
                        recordingId,
                        streamId,
                        sessionId,
                        startPosition,
                        stopPosition,
                        archive.getRecordingPosition(recordingId),
                        originalChannel));
            System.out.println("recordingCount=" + count);
        }
    }
}
