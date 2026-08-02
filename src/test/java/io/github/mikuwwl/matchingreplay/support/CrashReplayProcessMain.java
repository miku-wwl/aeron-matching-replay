package io.github.mikuwwl.matchingreplay.support;

import io.github.mikuwwl.matchingreplay.aeron.AeronArchiveClientFactory;
import io.github.mikuwwl.matchingreplay.aeron.AeronReplayCoordinator;
import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Test-only process which terminates in the middle of a real Archive replay,
 * immediately after forcing and atomically replacing a periodic checkpoint.
 */
public final class CrashReplayProcessMain
{
    public static final int HALT_EXIT_CODE = 77;

    private CrashReplayProcessMain()
    {
    }

    public static void main(final String[] args)
    {
        if (args.length != 8)
        {
            throw new IllegalArgumentException(
                "Expected: aeronDirectory checkpointDirectory recordingId checkpointKey " +
                "stopPosition expectedSequence expectedStateHash crashSequence");
        }

        final ReplayProperties properties = properties(
            Path.of(args[0]),
            Path.of(args[1]));
        final ReplayCommand command = new ReplayCommand(
            Long.parseLong(args[2]),
            args[3],
            Long.parseLong(args[4]),
            Long.parseLong(args[5]),
            Long.parseUnsignedLong(args[6]),
            "hard-crash-child");
        final CheckpointRepository checkpoints = new CrashAfterCheckpointRepository(
            properties,
            Long.parseLong(args[7]));
        final AeronReplayCoordinator coordinator = new AeronReplayCoordinator(
            new AeronArchiveClientFactory(properties),
            checkpoints,
            properties);

        coordinator.replay(command);
        throw new AssertionError("Replay completed without the configured hard crash");
    }

    private static ReplayProperties properties(
        final Path aeronDirectory,
        final Path checkpointDirectory)
    {
        final ReplayProperties properties = new ReplayProperties();
        properties.setAeronDirectory(aeronDirectory);
        properties.setCheckpointDirectory(checkpointDirectory);
        properties.setReplayChannel("aeron:ipc");
        properties.setReplayStreamId(1002);
        properties.setTimeout(Duration.ofSeconds(20));
        properties.setFragmentLimit(20);
        properties.setCheckpointEvery(50);
        properties.getArchive().setControlRequestChannel(
            EmbeddedArchiveFixture.LOCAL_CONTROL_CHANNEL);
        properties.getArchive().setControlRequestStreamId(
            EmbeddedArchiveFixture.LOCAL_CONTROL_STREAM_ID);
        properties.getArchive().setControlResponseChannel(
            EmbeddedArchiveFixture.CONTROL_RESPONSE_CHANNEL);
        return properties;
    }

    private static final class CrashAfterCheckpointRepository extends CheckpointRepository
    {
        private final long crashSequence;

        private CrashAfterCheckpointRepository(
            final ReplayProperties properties,
            final long crashSequence)
        {
            super(properties);
            this.crashSequence = crashSequence;
        }

        @Override
        public void save(final Checkpoint checkpoint)
        {
            super.save(checkpoint);
            if (checkpoint.lastAppliedEventSequence() == crashSequence)
            {
                System.out.println(
                    "CHECKPOINT_SAVED sequence=" + checkpoint.lastAppliedEventSequence() +
                    " position=" + checkpoint.lastAppliedAeronPosition());
                System.out.flush();
                Runtime.getRuntime().halt(HALT_EXIT_CODE);
            }
        }
    }
}
