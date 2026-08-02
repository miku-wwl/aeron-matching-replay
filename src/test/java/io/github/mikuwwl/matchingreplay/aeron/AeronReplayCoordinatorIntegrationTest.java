package io.github.mikuwwl.matchingreplay.aeron;

import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import io.github.mikuwwl.matchingreplay.support.EmbeddedArchiveFixture;
import io.github.mikuwwl.matchingreplay.support.CrashReplayProcessMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AeronReplayCoordinatorIntegrationTest
{
    @TempDir
    Path tempDirectory;

    @Test
    void hardCrashAfterCheckpointResumesAtNextEventAndMatchesUninterruptedHash()
        throws Exception
    {
        try (EmbeddedArchiveFixture upstream =
            new EmbeddedArchiveFixture(tempDirectory.resolve("upstream")))
        {
            final List<MatchingEvent> events = LongStream.rangeClosed(1, 1_000)
                .mapToObj(AeronReplayCoordinatorIntegrationTest::event)
                .toList();
            final EmbeddedArchiveFixture.Recording recording = upstream.record(events);
            final long crashSequence = 400;
            final long crashPosition = recording.positionAfterSequence(crashSequence);
            final long crashHash = recording.hashAfterSequence(crashSequence);
            assertNotEquals(
                crashSequence,
                crashPosition,
                "Business event sequence must not be confused with Aeron byte position");

            final Path checkpointDirectory = tempDirectory.resolve("service-checkpoints");
            final ReplayProperties properties = upstream.replayProperties(
                checkpointDirectory);
            final AeronReplayCoordinator uninterruptedCoordinator = new AeronReplayCoordinator(
                new AeronArchiveClientFactory(properties),
                new CheckpointRepository(properties),
                properties);
            final ReplayResult uninterrupted = uninterruptedCoordinator.replay(new ReplayCommand(
                recording.recordingId(),
                "uninterrupted-projection",
                recording.stopPosition(),
                recording.eventCount(),
                recording.expectedStateHash(),
                "uninterrupted-run"));
            assertTrue(uninterrupted.verificationPassed());

            final String checkpointKey = "crash-recovery-projection";
            final Path childLog = tempDirectory.resolve("crash-child.log");
            final Process child = startCrashChild(
                upstream,
                checkpointDirectory,
                recording,
                checkpointKey,
                crashSequence,
                childLog);
            final boolean exited = child.waitFor(30, TimeUnit.SECONDS);
            if (!exited)
            {
                child.destroyForcibly();
            }
            assertTrue(exited, "Crash child timed out; output:\n" + readLog(childLog));
            assertEquals(
                CrashReplayProcessMain.HALT_EXIT_CODE,
                child.exitValue(),
                "Crash child failed; output:\n" + readLog(childLog));

            final CheckpointRepository checkpointReader = new CheckpointRepository(properties);
            final Checkpoint crashCheckpoint = checkpointReader.find(checkpointKey).orElseThrow();
            assertEquals(crashSequence, crashCheckpoint.lastAppliedEventSequence());
            assertEquals(crashPosition, crashCheckpoint.lastAppliedAeronPosition());
            assertEquals(crashHash, crashCheckpoint.stateHash());

            // Fresh client factory, repository and coordinator model a service process restart.
            final AeronReplayCoordinator restartedCoordinator = new AeronReplayCoordinator(
                new AeronArchiveClientFactory(properties),
                new CheckpointRepository(properties),
                properties);
            final ReplayResult resumed = restartedCoordinator.replay(new ReplayCommand(
                recording.recordingId(),
                checkpointKey,
                recording.stopPosition(),
                recording.eventCount(),
                recording.expectedStateHash(),
                "post-crash-resume"));
            assertTrue(resumed.verificationPassed());
            assertEquals(crashPosition, resumed.replayStartPosition());
            assertEquals(crashSequence + 1, resumed.firstRecoveredSequence());
            assertEquals(1_000, resumed.lastRecoveredSequence());
            assertEquals(1_000, resumed.finalSequence());
            assertEquals(0, resumed.gaps());
            assertEquals(uninterrupted.stateHash(), resumed.stateHash());
            assertEquals(recording.expectedStateHash(), resumed.stateHash());
        }
    }

    private static Process startCrashChild(
        final EmbeddedArchiveFixture upstream,
        final Path checkpointDirectory,
        final EmbeddedArchiveFixture.Recording recording,
        final String checkpointKey,
        final long crashSequence,
        final Path childLog)
        throws Exception
    {
        final ReplayProperties childProperties = upstream.replayProperties(checkpointDirectory);
        final Path javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            isWindows() ? "java.exe" : "java");
        return new ProcessBuilder(
            javaExecutable.toString(),
            "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            CrashReplayProcessMain.class.getName(),
            childProperties.getAeronDirectory().toString(),
            checkpointDirectory.toString(),
            Long.toString(recording.recordingId()),
            checkpointKey,
            Long.toString(recording.stopPosition()),
            Long.toString(recording.eventCount()),
            Long.toUnsignedString(recording.expectedStateHash()),
            Long.toString(crashSequence))
            .redirectErrorStream(true)
            .redirectOutput(childLog.toFile())
            .start();
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String readLog(final Path path) throws Exception
    {
        return Files.exists(path) ? Files.readString(path) : "<no child output>";
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
