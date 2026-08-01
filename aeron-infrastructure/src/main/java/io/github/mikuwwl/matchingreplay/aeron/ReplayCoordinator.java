package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.Aeron;
import io.aeron.ChannelUri;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

import java.time.Duration;

public final class ReplayCoordinator
{
    private final RuntimePaths paths;
    private final String consumerName;
    private final Duration timeout;

    public ReplayCoordinator(
        final RuntimePaths paths,
        final String consumerName,
        final Duration timeout)
    {
        this.paths = paths;
        this.consumerName = consumerName;
        this.timeout = timeout;
    }

    public ReplayResult replay()
    {
        final long startedNs = System.nanoTime();
        final RunManifest manifest = new RunManifestStore(paths.currentManifest()).readRequired();
        final CheckpointStore checkpointStore = new CheckpointStore(paths.checkpoint(consumerName));
        final Checkpoint checkpoint = checkpointStore.read().orElseThrow(() ->
            new IllegalStateException("Checkpoint is missing: " + checkpointStore.path()));
        if (checkpoint.recordingId() != manifest.recordingId())
        {
            throw new IllegalStateException(
                "Checkpoint recordingId=" + checkpoint.recordingId() +
                " does not match manifest recordingId=" + manifest.recordingId());
        }

        final ProjectionState state = ProjectionState.from(checkpoint);
        try (Aeron aeron = AeronClientFactory.connectAeron(paths, "replay-coordinator");
            AeronArchive archive = AeronClientFactory.connectArchive(
                aeron,
                "replay-coordinator-archive",
                timeout))
        {
            final long recordingStartPosition = archive.getStartPosition(manifest.recordingId());
            long replayStopPosition = archive.getRecordingPosition(manifest.recordingId());
            if (replayStopPosition == AeronArchive.NULL_POSITION)
            {
                replayStopPosition = archive.getStopPosition(manifest.recordingId());
            }
            validatePosition(
                checkpoint.lastAppliedAeronPosition(),
                recordingStartPosition,
                replayStopPosition);

            final long replayStartPosition = checkpoint.lastAppliedAeronPosition();
            final long replayLength = replayStopPosition - replayStartPosition;
            long replaySessionId = Aeron.NULL_VALUE;
            long firstRecoveredSequence = 0;
            long lastRecoveredSequence = 0;

            if (replayLength > 0)
            {
                replaySessionId = archive.startReplay(
                    manifest.recordingId(),
                    replayStartPosition,
                    replayLength,
                    AeronChannels.REPLAY_CHANNEL,
                    AeronChannels.REPLAY_STREAM_ID);
                final String replaySessionChannel = ChannelUri.addSessionId(
                    AeronChannels.REPLAY_CHANNEL,
                    (int)replaySessionId);
                try (Subscription subscription = aeron.addSubscription(
                    replaySessionChannel,
                    AeronChannels.REPLAY_STREAM_ID))
                {
                    final CheckpointingFragmentHandler handler = new CheckpointingFragmentHandler(
                        state,
                        checkpointStore,
                        consumerName,
                        manifest::recordingId,
                        1,
                        0,
                        ignored -> { });
                    final FragmentAssembler assembler = new FragmentAssembler(handler);
                    final IdleStrategy idle = new BackoffIdleStrategy();
                    final long deadline = System.nanoTime() + timeout.toNanos();
                    while (state.lastAppliedAeronPosition() < replayStopPosition)
                    {
                        final int fragments = subscription.poll(assembler, 10);
                        idle.idle(fragments);
                        if (System.nanoTime() >= deadline)
                        {
                            throw new IllegalStateException(
                                "Replay timed out: recordingId=" + manifest.recordingId() +
                                ", currentPosition=" + state.lastAppliedAeronPosition() +
                                ", stopPosition=" + replayStopPosition);
                        }
                    }
                    firstRecoveredSequence = handler.firstAppliedSequence();
                    lastRecoveredSequence = handler.lastAppliedSequence();
                }
                finally
                {
                    archive.stopReplay(replaySessionId);
                }
            }

            final boolean passed =
                state.lastAppliedAeronPosition() >= replayStopPosition &&
                state.lastAppliedEventSequence() == manifest.lastEventSequence() &&
                state.gapCount() == 0 &&
                state.duplicateEventCount() == 0 &&
                state.stateHash() == manifest.expectedProjectionHash();
            return new ReplayResult(
                manifest.recordingId(),
                replayStartPosition,
                replayStopPosition,
                firstRecoveredSequence,
                lastRecoveredSequence,
                state.lastAppliedEventSequence(),
                state.gapCount(),
                state.duplicateEventCount(),
                state.stateHash(),
                Duration.ofNanos(System.nanoTime() - startedNs).toMillis(),
                passed);
        }
    }

    public static void validatePosition(
        final long checkpointPosition,
        final long recordingStartPosition,
        final long replayStopPosition)
    {
        if (recordingStartPosition == AeronArchive.NULL_POSITION ||
            replayStopPosition == AeronArchive.NULL_POSITION)
        {
            throw new IllegalStateException("Recording positions are unavailable");
        }
        if (checkpointPosition < recordingStartPosition || checkpointPosition > replayStopPosition)
        {
            throw new IllegalArgumentException(
                "Checkpoint position " + checkpointPosition +
                " is outside recording range [" + recordingStartPosition + ", " + replayStopPosition + "]");
        }
    }
}
