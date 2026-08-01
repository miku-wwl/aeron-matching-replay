package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;

public final class ArchiveNode
{
    private ArchiveNode()
    {
    }

    public static ArchivingMediaDriver launch(final RuntimePaths paths)
    {
        paths.createDirectories();
        final var errorHandler = (org.agrona.ErrorHandler)ex ->
        {
            System.err.println("ARCHIVE_NODE_ERROR " + ex);
            ex.printStackTrace(System.err);
        };
        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(paths.aeronDir().toString())
            .dirDeleteOnStart(false)
            .dirDeleteOnShutdown(false)
            .warnIfDirectoryExists(false)
            .threadingMode(ThreadingMode.SHARED)
            .spiesSimulateConnection(false)
            .errorHandler(errorHandler);
        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(paths.aeronDir().toString())
            .archiveDir(paths.archiveDir().toFile())
            .deleteArchiveOnStart(false)
            .controlChannel(AeronChannels.ARCHIVE_CONTROL_CHANNEL)
            .localControlChannel(AeronChannels.ARCHIVE_LOCAL_CONTROL_CHANNEL)
            .localControlStreamId(AeronChannels.ARCHIVE_LOCAL_CONTROL_STREAM_ID)
            .replicationChannel(AeronChannels.ARCHIVE_REPLICATION_CHANNEL)
            .recordingEventsEnabled(false)
            .fileSyncLevel(1)
            .catalogFileSyncLevel(1)
            .threadingMode(ArchiveThreadingMode.SHARED)
            .errorHandler(errorHandler);
        return ArchivingMediaDriver.launch(mediaDriverContext, archiveContext);
    }
}
