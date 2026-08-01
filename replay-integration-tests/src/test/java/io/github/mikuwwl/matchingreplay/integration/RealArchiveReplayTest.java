package io.github.mikuwwl.matchingreplay.integration;

import io.aeron.Aeron;
import io.aeron.ChannelUri;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.github.mikuwwl.matchingreplay.aeron.AeronChannels;
import io.github.mikuwwl.matchingreplay.aeron.AeronClientFactory;
import io.github.mikuwwl.matchingreplay.aeron.AeronMatchingEventPublisher;
import io.github.mikuwwl.matchingreplay.aeron.ArchiveNode;
import io.github.mikuwwl.matchingreplay.aeron.ArchiveRecordingSession;
import io.github.mikuwwl.matchingreplay.aeron.Checkpoint;
import io.github.mikuwwl.matchingreplay.aeron.ProjectionState;
import io.github.mikuwwl.matchingreplay.aeron.ReplayCoordinator;
import io.github.mikuwwl.matchingreplay.aeron.RuntimePaths;
import io.github.mikuwwl.matchingreplay.codec.MatchingEventSbeDispatcher;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealArchiveReplayTest
{
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path tempDir;

    @Test
    void recordsAndReplaysRealAeronStreamFromExactPositions()
    {
        final RuntimePaths paths = paths(tempDir).createDirectories();
        try (ArchivingMediaDriver ignored = ArchiveNode.launch(paths);
            Aeron liveAeron = AeronClientFactory.connectAeron(paths, "integration-live");
            Subscription liveSubscription = liveAeron.addSubscription(
                AeronChannels.LIVE_CHANNEL,
                AeronChannels.LIVE_STREAM_ID);
            ArchiveRecordingSession session = ArchiveRecordingSession.open(paths, TIMEOUT))
        {
            final AeronMatchingEventPublisher publisher =
                new AeronMatchingEventPublisher(session.publication(), TIMEOUT);
            final ProjectionState reference = new ProjectionState();
            long finalPosition = 0;
            for (long sequence = 1; sequence <= 1_000; sequence++)
            {
                final MatchingEvent event = event(sequence);
                finalPosition = publisher.publish(event);
                reference.apply(event, finalPosition);
            }
            assertEquals(1_000, publisher.eventsPublished());
            assertEquals(finalPosition, session.awaitRecorded(finalPosition, TIMEOUT));

            final LiveCapture live = captureLive(liveSubscription, 1_000);
            assertEquals(1L, live.events.getFirst().eventSequence());
            assertEquals(1_000L, live.events.getLast().eventSequence());
            final long checkpointPosition = live.positions.get(399);
            final long positionBeforeCheckpoint = live.positions.get(398);
            assertNotEquals(400, checkpointPosition);
            assertTrue(checkpointPosition > positionBeforeCheckpoint);

            session.stopRecording();
            final long stopPosition = session.archive().getStopPosition(session.recordingId());
            assertEquals(finalPosition, stopPosition);

            final ProjectionState fullState = new ProjectionState();
            final ReplayCapture fullReplay = replay(
                liveAeron,
                session.archive(),
                session.recordingId(),
                session.archive().getStartPosition(session.recordingId()),
                stopPosition,
                fullState);
            assertEquals(1_000, fullReplay.events.size());
            assertEquals(1L, fullReplay.events.getFirst().eventSequence());
            assertEquals(1_000L, fullReplay.events.getLast().eventSequence());
            assertEquals(reference.stateHash(), fullState.stateHash());

            final ProjectionState checkpointState = new ProjectionState();
            for (int index = 0; index < 400; index++)
            {
                checkpointState.apply(live.events.get(index), live.positions.get(index));
            }
            final Checkpoint durableCheckpoint = checkpointState.checkpoint("test-consumer", session.recordingId());
            final ProjectionState recoveredState = ProjectionState.from(durableCheckpoint);
            final ReplayCapture recovered = replay(
                liveAeron,
                session.archive(),
                session.recordingId(),
                checkpointPosition,
                stopPosition,
                recoveredState);
            assertEquals(401L, recovered.events.getFirst().eventSequence());
            assertEquals(1_000L, recovered.events.getLast().eventSequence());
            assertEquals(0, recoveredState.gapCount());
            assertEquals(0, recoveredState.duplicateEventCount());
            assertEquals(reference.stateHash(), recoveredState.stateHash());
            assertEquals(reference.lastAppliedEventSequence(), recoveredState.lastAppliedEventSequence());

            final ProjectionState duplicateState = ProjectionState.from(durableCheckpoint);
            final ReplayCapture duplicateReplay = replay(
                liveAeron,
                session.archive(),
                session.recordingId(),
                positionBeforeCheckpoint,
                stopPosition,
                duplicateState);
            assertEquals(400L, duplicateReplay.events.getFirst().eventSequence());
            assertEquals(1, duplicateState.duplicateEventCount());
            assertEquals(reference.stateHash(), duplicateState.stateHash());

            final long startPosition = session.archive().getStartPosition(session.recordingId());
            assertThrows(IllegalArgumentException.class, () ->
                ReplayCoordinator.validatePosition(startPosition - 1, startPosition, stopPosition));
            assertThrows(IllegalArgumentException.class, () ->
                ReplayCoordinator.validatePosition(stopPosition + 32, startPosition, stopPosition));
        }
    }

    private static LiveCapture captureLive(final Subscription subscription, final int expectedEvents)
    {
        final MatchingEventSbeDispatcher dispatcher = new MatchingEventSbeDispatcher();
        final List<MatchingEvent> events = new ArrayList<>(expectedEvents);
        final List<Long> positions = new ArrayList<>(expectedEvents);
        final FragmentAssembler assembler = new FragmentAssembler((buffer, offset, length, header) ->
        {
            events.add(dispatcher.decode(buffer, offset, length));
            positions.add(header.position());
        });
        pollUntil(subscription, assembler, () -> events.size() >= expectedEvents);
        return new LiveCapture(events, positions);
    }

    private static ReplayCapture replay(
        final Aeron aeron,
        final AeronArchive archive,
        final long recordingId,
        final long startPosition,
        final long stopPosition,
        final ProjectionState state)
    {
        final long replaySessionId = archive.startReplay(
            recordingId,
            startPosition,
            stopPosition - startPosition,
            AeronChannels.REPLAY_CHANNEL,
            AeronChannels.REPLAY_STREAM_ID);
        final String channel = ChannelUri.addSessionId(AeronChannels.REPLAY_CHANNEL, (int)replaySessionId);
        final List<MatchingEvent> events = new ArrayList<>();
        final MatchingEventSbeDispatcher dispatcher = new MatchingEventSbeDispatcher();
        try (Subscription subscription = aeron.addSubscription(channel, AeronChannels.REPLAY_STREAM_ID))
        {
            final FragmentAssembler assembler = new FragmentAssembler((buffer, offset, length, header) ->
            {
                final MatchingEvent event = dispatcher.decode(buffer, offset, length);
                events.add(event);
                state.apply(event, header.position());
            });
            pollUntil(subscription, assembler, () -> state.lastAppliedAeronPosition() >= stopPosition);
        }
        finally
        {
            archive.stopReplay(replaySessionId);
        }
        return new ReplayCapture(events);
    }

    private static void pollUntil(
        final Subscription subscription,
        final FragmentAssembler assembler,
        final Condition complete)
    {
        final long deadline = System.nanoTime() + TIMEOUT.toNanos();
        final IdleStrategy idle = new BackoffIdleStrategy();
        while (!complete.isTrue())
        {
            final int fragments = subscription.poll(assembler, 20);
            idle.idle(fragments);
            if (System.nanoTime() >= deadline)
            {
                throw new IllegalStateException("Timed out polling real Aeron stream");
            }
        }
    }

    private static MatchingEvent event(final long sequence)
    {
        final EventType type = switch ((int)(sequence & 3))
        {
            case 1 -> EventType.ORDER_ACCEPTED;
            case 2 -> EventType.TRADE_EXECUTED;
            case 3 -> EventType.ORDER_PARTIALLY_FILLED;
            default -> EventType.ORDER_FILLED;
        };
        return new MatchingEvent(
            (short)1,
            type,
            sequence,
            1_000_000 + sequence,
            10_000 + sequence,
            type == EventType.TRADE_EXECUTED ? 9_000 + sequence : 0,
            type == EventType.TRADE_EXECUTED ? sequence / 4 + 1 : 0,
            1,
            (sequence & 1) == 0 ? Side.BUY : Side.SELL,
            100_000 + sequence,
            10 + sequence,
            type == EventType.ORDER_FILLED ? 0 : 5);
    }

    private static RuntimePaths paths(final Path root)
    {
        return new RuntimePaths(
            root,
            root.resolve("aeron"),
            root.resolve("archive"),
            root.resolve("checkpoints"),
            root.resolve("manifests"),
            root.resolve("logs"),
            root.resolve("pids"));
    }

    private record LiveCapture(List<MatchingEvent> events, List<Long> positions)
    {
    }

    private record ReplayCapture(List<MatchingEvent> events)
    {
    }

    @FunctionalInterface
    private interface Condition
    {
        boolean isTrue();
    }
}
