package io.github.mikuwwl.matchingreplay.aeron;

import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import io.github.mikuwwl.matchingreplay.support.EmbeddedArchiveFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AeronReplayCoordinatorIntegrationTest
{
    @TempDir
    Path tempDirectory;

    @Test
    void replaysOneExternalRecordingAndResumesFromDurableCheckpoint()
    {
        try (EmbeddedArchiveFixture upstream =
            new EmbeddedArchiveFixture(tempDirectory.resolve("upstream")))
        {
            final List<MatchingEvent> events = LongStream.rangeClosed(1, 1_000)
                .mapToObj(AeronReplayCoordinatorIntegrationTest::event)
                .toList();
            final EmbeddedArchiveFixture.Recording recording = upstream.record(events);
            final ReplayProperties properties = upstream.replayProperties(
                tempDirectory.resolve("service-checkpoints"));
            final AeronArchiveClientFactory clients = new AeronArchiveClientFactory(properties);
            final CheckpointRepository checkpoints = new CheckpointRepository(properties);
            final AeronReplayCoordinator coordinator =
                new AeronReplayCoordinator(clients, checkpoints, properties);
            final ReplayCommand command = new ReplayCommand(
                recording.recordingId(),
                "orders-projection",
                recording.stopPosition(),
                recording.eventCount(),
                recording.expectedStateHash(),
                "integration-test");

            final ReplayResult first = coordinator.replay(command);
            assertTrue(first.verificationPassed());
            assertEquals(1, first.firstRecoveredSequence());
            assertEquals(1_000, first.lastRecoveredSequence());
            assertEquals(1_000, first.finalSequence());
            assertEquals(recording.stopPosition(), first.replayStopPosition());
            assertEquals(recording.expectedStateHash(), first.stateHash());
            assertEquals(0, first.gaps());

            final ReplayResult resumed = coordinator.replay(command);
            assertTrue(resumed.verificationPassed());
            assertEquals(recording.stopPosition(), resumed.replayStartPosition());
            assertEquals(0, resumed.firstRecoveredSequence());
            assertEquals(1_000, resumed.finalSequence());
            assertEquals(recording.expectedStateHash(), resumed.stateHash());
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
}
