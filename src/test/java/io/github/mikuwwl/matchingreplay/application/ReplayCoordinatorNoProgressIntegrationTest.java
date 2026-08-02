package io.github.mikuwwl.matchingreplay.application;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import io.github.mikuwwl.matchingreplay.aeron.AeronArchiveClientFactory;
import io.github.mikuwwl.matchingreplay.aeron.AeronReplayCoordinator;
import io.github.mikuwwl.matchingreplay.aeron.MonotonicClock;
import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.checkpoint.CompletionProofRepository;
import io.github.mikuwwl.matchingreplay.codec.MatchingEventSbeEncoder;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayCoordinatorNoProgressIntegrationTest
{
    @TempDir
    Path tempDirectory;

    @Test
    void stalledReplayReturnsNoProgressTimeout()
    {
        final ReplayProperties properties = properties();
        final AeronArchiveClientFactory clientFactory =
            mock(AeronArchiveClientFactory.class);
        final Aeron aeron = mock(Aeron.class);
        final AeronArchive archive = mock(AeronArchive.class);
        final Subscription subscription = mock(Subscription.class);
        final Header header = mock(Header.class);
        final UnsafeBuffer encoded = new UnsafeBuffer(
            new byte[MatchingEventSbeEncoder.MAX_ENCODED_LENGTH]);
        final MatchingEvent event = event(1);
        final int encodedLength =
            new MatchingEventSbeEncoder().encode(event, encoded, 0);
        final AtomicLong nanoTime = new AtomicLong();
        final MonotonicClock monotonicClock = nanoTime::get;
        final AtomicInteger polls = new AtomicInteger();

        when(clientFactory.connectAeron(anyString())).thenReturn(aeron);
        when(clientFactory.connectArchive(any(), anyString())).thenReturn(archive);
        when(archive.getStartPosition(42)).thenReturn(0L);
        when(archive.getRecordingPosition(42)).thenReturn(128L);
        when(archive.startReplay(42, 0, 128, "aeron:ipc", 1002))
            .thenReturn(77L);
        when(aeron.addSubscription(anyString(), anyInt())).thenReturn(subscription);
        when(header.flags()).thenReturn(FrameDescriptor.UNFRAGMENTED);
        when(header.position()).thenReturn(64L);
        when(subscription.poll(any(FragmentHandler.class), anyInt()))
            .thenAnswer(invocation ->
            {
                final int poll = polls.getAndIncrement();
                if (poll == 0)
                {
                    nanoTime.addAndGet(Duration.ofMillis(1).toNanos());
                    final FragmentHandler handler = invocation.getArgument(0);
                    handler.onFragment(encoded, 0, encodedLength, header);
                    return 1;
                }
                nanoTime.addAndGet(Duration.ofMillis(6).toNanos());
                return 0;
            });

        final CheckpointRepository checkpoints =
            new CheckpointRepository(properties);
        final CompletionProofRepository proofs =
            new CompletionProofRepository(properties);
        final AeronReplayCoordinator coordinator = new AeronReplayCoordinator(
            clientFactory,
            checkpoints,
            proofs,
            properties,
            monotonicClock);
        final ReplayJobManager manager = new ReplayJobManager(
            coordinator,
            Runnable::run,
            Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC));

        final ReplayJobSnapshot job = manager.start(new ReplayCommand(
            42,
            "stalled",
            128L,
            2,
            99,
            "stalled-test"));

        assertEquals(ReplayJobState.FAILED, job.state());
        assertEquals(ReplayFailureCode.NO_PROGRESS_TIMEOUT, job.failure().code());
        assertEquals(42, job.failure().recordingId());
        assertEquals(64, job.failure().currentPosition());
        assertEquals(128, job.failure().replayStopPosition());
        assertEquals(1, job.failure().lastAppliedEventSequence());
        assertEquals(6, job.failure().timeSinceLastProgressMillis());
        assertEquals(5, job.failure().configuredNoProgressTimeoutMillis());
        assertNotNull(job.progress());
        assertNotNull(job.progress().lastProgressAt());
        assertEquals(64, job.progress().currentPosition());
        final Checkpoint checkpoint = checkpoints.find("stalled").orElseThrow();
        assertEquals(64, checkpoint.lastAppliedAeronPosition());
        assertEquals(1, checkpoint.lastAppliedEventSequence());
        assertTrue(proofs.findByCheckpointKey("stalled").isEmpty());
    }

    private ReplayProperties properties()
    {
        final ReplayProperties properties = new ReplayProperties();
        properties.setAeronDirectory(tempDirectory.resolve("aeron"));
        properties.setCheckpointDirectory(tempDirectory.resolve("checkpoints"));
        properties.setReplayChannel("aeron:ipc");
        properties.setReplayStreamId(1002);
        properties.setNoProgressTimeout(Duration.ofMillis(5));
        properties.setArchiveRequestTimeout(Duration.ofSeconds(1));
        properties.setCheckpointEveryProcessedMessages(1);
        return properties;
    }

    private static MatchingEvent event(final long sequence)
    {
        return new MatchingEvent(
            (short)2,
            EventType.ORDER_ACCEPTED,
            sequence,
            sequence,
            sequence,
            0,
            0,
            1,
            Side.BUY,
            100,
            10,
            10,
            1);
    }
}
