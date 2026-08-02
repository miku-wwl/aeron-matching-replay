package io.github.mikuwwl.matchingreplay.support;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.github.mikuwwl.matchingreplay.codec.MatchingEventSbeEncoder;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.ReplayDigest;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class EmbeddedArchiveFixture implements AutoCloseable
{
    public static final String LIVE_CHANNEL = "aeron:ipc";
    public static final int LIVE_STREAM_ID = 1001;
    public static final String LOCAL_CONTROL_CHANNEL = "aeron:ipc?term-length=64k";
    public static final int LOCAL_CONTROL_STREAM_ID = 10;
    public static final String CONTROL_RESPONSE_CHANNEL = "aeron:ipc";

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final Path root;
    private final Path aeronDirectory;
    private final ArchivingMediaDriver driver;

    public EmbeddedArchiveFixture(final Path root)
    {
        this.root = root.toAbsolutePath().normalize();
        aeronDirectory = this.root.resolve("aeron");
        try
        {
            Files.createDirectories(aeronDirectory);
            Files.createDirectories(this.root.resolve("archive"));
        }
        catch (final Exception ex)
        {
            throw new IllegalStateException("Failed to create test Archive directories", ex);
        }

        final var errorHandler = (org.agrona.ErrorHandler)ex ->
        {
            throw new IllegalStateException("Embedded Archive failure", ex);
        };
        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirectory.toString())
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .warnIfDirectoryExists(false)
            .threadingMode(ThreadingMode.SHARED)
            .spiesSimulateConnection(false)
            .errorHandler(errorHandler);
        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDirectory.toString())
            .archiveDir(this.root.resolve("archive").toFile())
            .deleteArchiveOnStart(true)
            .controlChannel("aeron:udp?endpoint=localhost:0")
            .localControlChannel(LOCAL_CONTROL_CHANNEL)
            .localControlStreamId(LOCAL_CONTROL_STREAM_ID)
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            .recordingEventsEnabled(false)
            .threadingMode(ArchiveThreadingMode.SHARED)
            .errorHandler(errorHandler);
        driver = ArchivingMediaDriver.launch(mediaDriverContext, archiveContext);
    }

    public Recording record(final List<MatchingEvent> events)
    {
        final MatchingEventSbeEncoder encoder = new MatchingEventSbeEncoder();
        final UnsafeBuffer buffer = new UnsafeBuffer(
            new byte[MatchingEventSbeEncoder.MAX_ENCODED_LENGTH]);
        final List<byte[]> messages = new ArrayList<>(events.size());
        final List<Long> replayDigests = new ArrayList<>(events.size());
        long replayDigest = ReplayDigest.INITIAL_VALUE;
        for (final MatchingEvent event : events)
        {
            final int length = encoder.encode(event, buffer, 0);
            final byte[] message = new byte[length];
            buffer.getBytes(0, message);
            messages.add(message);
            replayDigest = ReplayDigest.mixEvent(replayDigest, event);
            replayDigests.add(replayDigest);
        }
        return recordEncoded(messages, replayDigests);
    }

    public Recording recordRaw(final List<byte[]> messages)
    {
        return recordEncoded(
            messages,
            java.util.Collections.nCopies(
                messages.size(),
                ReplayDigest.INITIAL_VALUE));
    }

    public LiveRecording startLiveRecording()
    {
        final Aeron aeron = connectAeron("live-test-publisher");
        final AeronArchive archive = connectArchive(aeron, "live-test-archive");
        final long recordingSubscriptionId = archive.startRecording(
            LIVE_CHANNEL,
            LIVE_STREAM_ID,
            SourceLocation.LOCAL);
        final ExclusivePublication publication = aeron.addExclusivePublication(
            LIVE_CHANNEL,
            LIVE_STREAM_ID);
        try
        {
            await(() -> publication.isConnected(), "live publication connection");
            final int counterId = awaitRecordingCounter(
                aeron.countersReader(),
                publication.sessionId(),
                archive.archiveId());
            final long recordingId = RecordingPos.getRecordingId(
                aeron.countersReader(),
                counterId);
            return new LiveRecording(
                aeron,
                archive,
                publication,
                recordingSubscriptionId,
                counterId,
                recordingId);
        }
        catch (final RuntimeException ex)
        {
            publication.close();
            archive.tryStopRecording(recordingSubscriptionId);
            archive.close();
            aeron.close();
            throw ex;
        }
    }

    private Recording recordEncoded(
        final List<byte[]> messages,
        final List<Long> replayDigests)
    {
        try (Aeron aeron = connectAeron("upstream-test-publisher");
            AeronArchive archive = connectArchive(aeron, "upstream-test-archive"))
        {
            final long recordingSubscriptionId = archive.startRecording(
                LIVE_CHANNEL,
                LIVE_STREAM_ID,
                SourceLocation.LOCAL);
            try (ExclusivePublication publication = aeron.addExclusivePublication(
                LIVE_CHANNEL,
                LIVE_STREAM_ID))
            {
                await(() -> publication.isConnected(), "publication connection");
                final int counterId = awaitRecordingCounter(
                    aeron.countersReader(),
                    publication.sessionId(),
                    archive.archiveId());
                final long recordingId = RecordingPos.getRecordingId(aeron.countersReader(), counterId);
                final List<Long> eventEndPositions = new ArrayList<>(messages.size());
                long finalPosition = 0;
                for (int index = 0; index < messages.size(); index++)
                {
                    final byte[] message = messages.get(index);
                    final UnsafeBuffer buffer = new UnsafeBuffer(message);
                    finalPosition = offer(
                        publication,
                        buffer,
                        message.length,
                        index + 1L);
                    eventEndPositions.add(finalPosition);
                }
                final long requiredPosition = finalPosition;
                await(
                    () -> aeron.countersReader().getCounterValue(counterId) >= requiredPosition,
                    "Archive recording position");
                archive.stopRecording(recordingSubscriptionId);
                return new Recording(
                    recordingId,
                    archive.getStartPosition(recordingId),
                    finalPosition,
                    messages.size(),
                    replayDigests.isEmpty() ?
                        ReplayDigest.INITIAL_VALUE :
                        replayDigests.getLast(),
                    eventEndPositions,
                    replayDigests);
            }
            finally
            {
                archive.tryStopRecording(recordingSubscriptionId);
            }
        }
    }

    public ReplayProperties replayProperties(final Path checkpointDirectory)
    {
        final ReplayProperties properties = new ReplayProperties();
        properties.setAeronDirectory(aeronDirectory);
        properties.setCheckpointDirectory(checkpointDirectory);
        properties.setReplayChannel("aeron:ipc");
        properties.setReplayStreamId(1002);
        properties.setNoProgressTimeout(TIMEOUT);
        properties.setArchiveRequestTimeout(TIMEOUT);
        properties.setFragmentLimit(20);
        properties.setCheckpointEveryProcessedMessages(50);
        properties.getArchive().setControlRequestChannel(LOCAL_CONTROL_CHANNEL);
        properties.getArchive().setControlRequestStreamId(LOCAL_CONTROL_STREAM_ID);
        properties.getArchive().setControlResponseChannel(CONTROL_RESPONSE_CHANNEL);
        return properties;
    }

    @Override
    public void close()
    {
        driver.close();
    }

    private Aeron connectAeron(final String clientName)
    {
        return Aeron.connect(
            new Aeron.Context()
                .aeronDirectoryName(aeronDirectory.toString())
                .clientName(clientName));
    }

    private static AeronArchive connectArchive(final Aeron aeron, final String clientName)
    {
        return AeronArchive.connect(
            new AeronArchive.Context()
                .aeron(aeron)
                .ownsAeronClient(false)
                .clientName(clientName)
                .controlRequestChannel(LOCAL_CONTROL_CHANNEL)
                .controlRequestStreamId(LOCAL_CONTROL_STREAM_ID)
                .controlResponseChannel(CONTROL_RESPONSE_CHANNEL)
                .messageTimeoutNs(TIMEOUT.toNanos()));
    }

    private static int awaitRecordingCounter(
        final CountersReader counters,
        final int sessionId,
        final long archiveId)
    {
        final int[] counterId = { CountersReader.NULL_COUNTER_ID };
        await(
            () ->
            {
                counterId[0] = RecordingPos.findCounterIdBySession(counters, sessionId, archiveId);
                return counterId[0] != CountersReader.NULL_COUNTER_ID;
            },
            "recording counter");
        return counterId[0];
    }

    private static long offer(
        final ExclusivePublication publication,
        final UnsafeBuffer buffer,
        final int length,
        final long eventSequence)
    {
        final long deadline = System.nanoTime() + TIMEOUT.toNanos();
        final IdleStrategy idle = new BackoffIdleStrategy();
        long result;
        while ((result = publication.offer(buffer, 0, length)) < 0)
        {
            if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED)
            {
                throw new IllegalStateException(
                    "Fatal publication result=" + result + " at eventSequence=" + eventSequence);
            }
            if (System.nanoTime() >= deadline)
            {
                throw new IllegalStateException(
                    "Timed out publishing eventSequence=" + eventSequence + ", result=" + result);
            }
            idle.idle();
        }
        return result;
    }

    private static void await(final Condition condition, final String description)
    {
        final long deadline = System.nanoTime() + TIMEOUT.toNanos();
        final IdleStrategy idle = new BackoffIdleStrategy();
        while (!condition.isTrue())
        {
            if (System.nanoTime() >= deadline)
            {
                throw new IllegalStateException("Timed out waiting for " + description);
            }
            idle.idle();
        }
    }

    public record Recording(
        long recordingId,
        long startPosition,
        long stopPosition,
        long eventCount,
        long expectedReplayDigest,
        List<Long> eventEndPositions,
        List<Long> replayDigests)
    {
        public Recording
        {
            eventEndPositions = List.copyOf(eventEndPositions);
            replayDigests = List.copyOf(replayDigests);
            if (eventEndPositions.size() != eventCount || replayDigests.size() != eventCount)
            {
                throw new IllegalArgumentException(
                    "Every event must have a position and replay digest");
            }
        }

        public long positionAfterSequence(final long eventSequence)
        {
            return eventEndPositions.get(indexOf(eventSequence));
        }

        public long digestAfterSequence(final long eventSequence)
        {
            return replayDigests.get(indexOf(eventSequence));
        }

        private int indexOf(final long eventSequence)
        {
            if (eventSequence < 1 || eventSequence > eventCount)
            {
                throw new IllegalArgumentException(
                    "eventSequence must be within [1, " + eventCount + "]");
            }
            return Math.toIntExact(eventSequence - 1);
        }
    }

    public final class LiveRecording implements AutoCloseable
    {
        private final Aeron aeron;
        private final AeronArchive archive;
        private final ExclusivePublication publication;
        private final long recordingSubscriptionId;
        private final int counterId;
        private final long recordingId;
        private final MatchingEventSbeEncoder encoder = new MatchingEventSbeEncoder();
        private final UnsafeBuffer encodeBuffer = new UnsafeBuffer(
            new byte[MatchingEventSbeEncoder.MAX_ENCODED_LENGTH]);
        private final List<Long> eventEndPositions = new ArrayList<>();
        private final List<Long> replayDigests = new ArrayList<>();

        private long replayDigest = ReplayDigest.INITIAL_VALUE;
        private boolean closed;

        private LiveRecording(
            final Aeron aeron,
            final AeronArchive archive,
            final ExclusivePublication publication,
            final long recordingSubscriptionId,
            final int counterId,
            final long recordingId)
        {
            this.aeron = aeron;
            this.archive = archive;
            this.publication = publication;
            this.recordingSubscriptionId = recordingSubscriptionId;
            this.counterId = counterId;
            this.recordingId = recordingId;
        }

        public void publish(final List<MatchingEvent> events)
        {
            ensureOpen();
            for (final MatchingEvent event : events)
            {
                final int length = encoder.encode(event, encodeBuffer, 0);
                final long position = offer(
                    publication,
                    encodeBuffer,
                    length,
                    event.eventSequence());
                eventEndPositions.add(position);
                replayDigest = ReplayDigest.mixEvent(replayDigest, event);
                replayDigests.add(replayDigest);
            }
            final long publishedPosition = publication.position();
            await(
                () -> aeron.countersReader().getCounterValue(counterId) >=
                    publishedPosition,
                "live Archive recording position");
        }

        public Recording captureBoundary()
        {
            ensureOpen();
            final long stopPosition =
                aeron.countersReader().getCounterValue(counterId);
            final long startPosition = archive.getStartPosition(recordingId);
            return new Recording(
                recordingId,
                startPosition,
                stopPosition,
                eventEndPositions.size(),
                replayDigest,
                eventEndPositions,
                replayDigests);
        }

        public boolean isLive()
        {
            return !closed &&
                archive.getRecordingPosition(recordingId) != AeronArchive.NULL_POSITION;
        }

        @Override
        public void close()
        {
            if (closed)
            {
                return;
            }
            closed = true;
            try
            {
                archive.tryStopRecording(recordingSubscriptionId);
            }
            finally
            {
                publication.close();
                archive.close();
                aeron.close();
            }
        }

        private void ensureOpen()
        {
            if (closed)
            {
                throw new IllegalStateException("Live recording is closed");
            }
        }
    }

    @FunctionalInterface
    private interface Condition
    {
        boolean isTrue();
    }
}
