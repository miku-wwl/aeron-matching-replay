package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.status.CountersReader;

import java.time.Duration;

public final class ArchiveRecordingSession implements AutoCloseable
{
    private final Aeron aeron;
    private final AeronArchive archive;
    private final ExclusivePublication publication;
    private final long recordingSubscriptionId;
    private final int recordingCounterId;
    private final long recordingId;
    private boolean recordingStopped;

    private ArchiveRecordingSession(
        final Aeron aeron,
        final AeronArchive archive,
        final ExclusivePublication publication,
        final long recordingSubscriptionId,
        final int recordingCounterId,
        final long recordingId)
    {
        this.aeron = aeron;
        this.archive = archive;
        this.publication = publication;
        this.recordingSubscriptionId = recordingSubscriptionId;
        this.recordingCounterId = recordingCounterId;
        this.recordingId = recordingId;
    }

    public static ArchiveRecordingSession open(final RuntimePaths paths, final Duration timeout)
    {
        final Aeron aeron = AeronClientFactory.connectAeron(paths, "matching-engine");
        AeronArchive archive = null;
        ExclusivePublication publication = null;
        long subscriptionId = Aeron.NULL_VALUE;
        try
        {
            archive = AeronClientFactory.connectArchive(aeron, "matching-engine-archive", timeout);
            subscriptionId = archive.startRecording(
                AeronChannels.LIVE_CHANNEL,
                AeronChannels.LIVE_STREAM_ID,
                SourceLocation.LOCAL);
            publication = aeron.addExclusivePublication(
                AeronChannels.LIVE_CHANNEL,
                AeronChannels.LIVE_STREAM_ID);
            awaitConnected(publication, timeout);

            final int counterId = awaitRecordingCounter(
                aeron.countersReader(),
                publication.sessionId(),
                archive.archiveId(),
                timeout);
            final long recordingId = RecordingPos.getRecordingId(aeron.countersReader(), counterId);
            return new ArchiveRecordingSession(
                aeron,
                archive,
                publication,
                subscriptionId,
                counterId,
                recordingId);
        }
        catch (final Throwable ex)
        {
            if (archive != null && subscriptionId != Aeron.NULL_VALUE)
            {
                archive.tryStopRecording(subscriptionId);
            }
            if (publication != null)
            {
                publication.close();
            }
            if (archive != null)
            {
                archive.close();
            }
            aeron.close();
            throw ex;
        }
    }

    private static void awaitConnected(
        final ExclusivePublication publication,
        final Duration timeout)
    {
        final long deadline = System.nanoTime() + timeout.toNanos();
        final IdleStrategy idle = new BackoffIdleStrategy();
        while (!publication.isConnected())
        {
            if (System.nanoTime() >= deadline)
            {
                throw new IllegalStateException("Timed out waiting for live ExclusivePublication to connect");
            }
            idle.idle();
        }
    }

    private static int awaitRecordingCounter(
        final CountersReader counters,
        final int sessionId,
        final long archiveId,
        final Duration timeout)
    {
        final long deadline = System.nanoTime() + timeout.toNanos();
        final IdleStrategy idle = new BackoffIdleStrategy();
        int counterId;
        while ((counterId = RecordingPos.findCounterIdBySession(counters, sessionId, archiveId)) ==
            CountersReader.NULL_COUNTER_ID)
        {
            if (System.nanoTime() >= deadline)
            {
                throw new IllegalStateException(
                    "Timed out waiting for Archive recording counter for sessionId=" + sessionId);
            }
            idle.idle();
        }
        return counterId;
    }

    public long awaitRecorded(final long requiredPosition, final Duration timeout)
    {
        final long deadline = System.nanoTime() + timeout.toNanos();
        final IdleStrategy idle = new BackoffIdleStrategy();
        long position;
        while ((position = recordingPosition()) < requiredPosition)
        {
            if (System.nanoTime() >= deadline)
            {
                throw new IllegalStateException(
                    "Timed out waiting for Archive: recordingId=" + recordingId +
                    ", requiredPosition=" + requiredPosition + ", actualPosition=" + position);
            }
            idle.idle();
        }
        return position;
    }

    public long recordingPosition()
    {
        if (RecordingPos.isActive(aeron.countersReader(), recordingCounterId, recordingId))
        {
            return aeron.countersReader().getCounterValue(recordingCounterId);
        }
        final long position = archive.getRecordingPosition(recordingId);
        return position == AeronArchive.NULL_POSITION ? archive.getStopPosition(recordingId) : position;
    }

    public void stopRecording()
    {
        if (!recordingStopped)
        {
            archive.stopRecording(recordingSubscriptionId);
            recordingStopped = true;
        }
    }

    public ExclusivePublication publication()
    {
        return publication;
    }

    public AeronArchive archive()
    {
        return archive;
    }

    public long recordingId()
    {
        return recordingId;
    }

    @Override
    public void close()
    {
        try
        {
            if (!recordingStopped)
            {
                archive.tryStopRecording(recordingSubscriptionId);
            }
        }
        finally
        {
            publication.close();
            archive.close();
            aeron.close();
        }
    }
}
