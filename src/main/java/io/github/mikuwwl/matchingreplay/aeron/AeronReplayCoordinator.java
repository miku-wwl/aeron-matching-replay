package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.Aeron;
import io.aeron.ChannelUri;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.projection.ProjectionState;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AeronReplayCoordinator
{
    private final AeronArchiveClientFactory clientFactory;
    private final CheckpointRepository checkpoints;
    private final ReplayProperties properties;

    public AeronReplayCoordinator(
        final AeronArchiveClientFactory clientFactory,
        final CheckpointRepository checkpoints,
        final ReplayProperties properties)
    {
        this.clientFactory = clientFactory;
        this.checkpoints = checkpoints;
        this.properties = properties;
    }

    public ReplayResult replay(final ReplayCommand command)
    {
        final long startedNs = System.nanoTime();
        final String clientName = "replay-" + command.checkpointKey();
        try (Aeron aeron = clientFactory.connectAeron(clientName);
            AeronArchive archive = clientFactory.connectArchive(aeron, clientName + "-archive"))
        {
            final long recordingStart = archive.getStartPosition(command.recordingId());
            final long recordingAvailable = availablePosition(archive, command.recordingId());
            if (recordingStart == AeronArchive.NULL_POSITION ||
                recordingAvailable == AeronArchive.NULL_POSITION)
            {
                throw new IllegalArgumentException(
                    "Unknown or unavailable recordingId=" + command.recordingId());
            }

            final long replayStop = command.stopPosition() == null ?
                recordingAvailable : command.stopPosition();
            if (replayStop > recordingAvailable)
            {
                throw new IllegalArgumentException(
                    "Requested stopPosition=" + replayStop +
                    " is beyond recorded position=" + recordingAvailable);
            }

            final Checkpoint checkpoint = loadCheckpoint(command, recordingStart);
            validatePosition(
                checkpoint.lastAppliedAeronPosition(),
                recordingStart,
                replayStop);
            final ProjectionState state = ProjectionState.from(checkpoint);
            final long replayStart = checkpoint.lastAppliedAeronPosition();
            final ReplayFragmentHandler handler = new ReplayFragmentHandler(
                state,
                checkpoints,
                command.checkpointKey(),
                command.recordingId(),
                properties.getCheckpointEvery());

            replayRange(aeron, archive, command.recordingId(), replayStart, replayStop, state, handler);
            handler.save();

            final boolean passed =
                state.lastAppliedAeronPosition() >= replayStop &&
                state.gapCount() == 0 &&
                command.expectedLastEventSequence() == state.lastAppliedEventSequence() &&
                command.expectedStateHash() == state.stateHash();
            return new ReplayResult(
                command.recordingId(),
                command.checkpointKey(),
                replayStart,
                replayStop,
                handler.firstAppliedSequence(),
                handler.lastAppliedSequence(),
                state.lastAppliedEventSequence(),
                state.appliedEventCount(),
                state.gapCount(),
                state.duplicateEventCount(),
                state.stateHash(),
                Duration.ofNanos(System.nanoTime() - startedNs).toMillis(),
                passed);
        }
    }

    private Checkpoint loadCheckpoint(
        final ReplayCommand command,
        final long recordingStart)
    {
        return checkpoints.find(command.checkpointKey())
            .map(checkpoint ->
            {
                if (checkpoint.recordingId() != command.recordingId())
                {
                    throw new IllegalStateException(
                        "Checkpoint recordingId=" + checkpoint.recordingId() +
                        " does not match requested recordingId=" + command.recordingId());
                }
                return checkpoint;
            })
            .orElseGet(() -> Checkpoint.initial(
                command.checkpointKey(),
                command.recordingId(),
                recordingStart));
    }

    private void replayRange(
        final Aeron aeron,
        final AeronArchive archive,
        final long recordingId,
        final long replayStart,
        final long replayStop,
        final ProjectionState state,
        final ReplayFragmentHandler handler)
    {
        final long replayLength = replayStop - replayStart;
        if (replayLength == 0)
        {
            return;
        }

        final long replaySessionId = archive.startReplay(
            recordingId,
            replayStart,
            replayLength,
            properties.getReplayChannel(),
            properties.getReplayStreamId());
        final String replaySessionChannel = ChannelUri.addSessionId(
            properties.getReplayChannel(),
            (int)replaySessionId);
        try (Subscription subscription = aeron.addSubscription(
            replaySessionChannel,
            properties.getReplayStreamId()))
        {
            pollReplay(subscription, state, handler, replayStop);
        }
        finally
        {
            archive.stopReplay(replaySessionId);
        }
    }

    private void pollReplay(
        final Subscription subscription,
        final ProjectionState state,
        final ReplayFragmentHandler handler,
        final long replayStop)
    {
        final FragmentAssembler assembler = new FragmentAssembler(handler);
        final IdleStrategy idle = new BackoffIdleStrategy();
        final long deadline = System.nanoTime() + properties.getTimeout().toNanos();
        while (state.lastAppliedAeronPosition() < replayStop)
        {
            final int fragments = subscription.poll(assembler, properties.getFragmentLimit());
            idle.idle(fragments);
            if (System.nanoTime() >= deadline)
            {
                throw new IllegalStateException(
                    "Replay timed out at position=" + state.lastAppliedAeronPosition() +
                    ", expectedStop=" + replayStop);
            }
        }
    }

    private static long availablePosition(final AeronArchive archive, final long recordingId)
    {
        final long recordingPosition = archive.getRecordingPosition(recordingId);
        return recordingPosition == AeronArchive.NULL_POSITION ?
            archive.getStopPosition(recordingId) : recordingPosition;
    }

    static void validatePosition(
        final long checkpointPosition,
        final long recordingStart,
        final long replayStop)
    {
        if (checkpointPosition < recordingStart || checkpointPosition > replayStop)
        {
            throw new IllegalArgumentException(
                "Checkpoint position=" + checkpointPosition +
                " is outside replay range=[" + recordingStart + ", " + replayStop + "]");
        }
    }

}
