package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.archive.ArchivingMediaDriver;
import org.agrona.concurrent.ShutdownSignalBarrier;

public final class ArchiveNodeMain
{
    private ArchiveNodeMain()
    {
    }

    public static void main(final String[] args)
    {
        final RuntimePaths paths = RuntimePaths.resolve().createDirectories();
        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();
        Runtime.getRuntime().addShutdownHook(new Thread(barrier::signalAll, "archive-shutdown"));

        System.out.println("aeronDirectory=" + paths.aeronDir());
        System.out.println("archiveDirectory=" + paths.archiveDir());
        try (ArchivingMediaDriver ignored = ArchiveNode.launch(paths))
        {
            System.out.println("ARCHIVE_READY");
            System.out.flush();
            barrier.await();
        }
        catch (final Throwable ex)
        {
            System.err.println("ARCHIVE_FAILED " + ex);
            ex.printStackTrace(System.err);
            throw ex;
        }
    }
}
