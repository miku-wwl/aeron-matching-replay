package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.Aeron;
import io.aeron.ChannelUri;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.checkpoint.CompletionProof;
import io.github.mikuwwl.matchingreplay.checkpoint.CompletionProofRepository;
import io.github.mikuwwl.matchingreplay.checkpoint.CompletionVerificationStatus;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import io.github.mikuwwl.matchingreplay.projection.ProjectionState;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class AeronReplayCoordinator implements ReplayEngine
{
    private final AeronArchiveClientFactory clientFactory;
    private final CheckpointRepository checkpoints;
    private final CompletionProofRepository completionProofs;
    private final ReplayProperties properties;

    @Autowired
    public AeronReplayCoordinator(
        final AeronArchiveClientFactory clientFactory,
        final CheckpointRepository checkpoints,
        final CompletionProofRepository completionProofs,
        final ReplayProperties properties)
    {
        this.clientFactory = clientFactory;
        this.checkpoints = checkpoints;
        this.completionProofs = completionProofs;
        this.properties = properties;
    }

    public AeronReplayCoordinator(
        final AeronArchiveClientFactory clientFactory,
        final CheckpointRepository checkpoints,
        final ReplayProperties properties)
    {
        this(
            clientFactory,
            checkpoints,
            new CompletionProofRepository(properties),
            properties);
    }

    public ReplayResult replay(final ReplayCommand command)
    {
        return replay(command, ReplayProgressListener.none());
    }

    @Override
    public ReplayResult replay(
        final ReplayCommand command,
        final ReplayProgressListener progressListener)
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
                throw new ReplayException(
                    ReplayFailure.basic(
                        ReplayFailureCode.RECORDING_NOT_FOUND,
                        "Unknown or unavailable recordingId=" + command.recordingId())
                        .withReplayContext(command.recordingId(), 0, 0, 0));
            }

            final long replayStop = command.stopPosition() == null ?
                recordingAvailable : command.stopPosition();
            if (replayStop < recordingStart || replayStop > recordingAvailable)
            {
                throw new ReplayException(
                    ReplayFailure.basic(
                        ReplayFailureCode.INVALID_REPLAY_RANGE,
                        "Requested stopPosition=" + replayStop +
                            " is outside recorded range=[" + recordingStart +
                            ", " + recordingAvailable + "]")
                        .withReplayContext(
                            command.recordingId(),
                            recordingStart,
                            replayStop,
                            0));
            }

            final Checkpoint checkpoint = loadCheckpoint(command, recordingStart);
            final ProjectionState state = ProjectionState.from(checkpoint);
            try
            {
                validatePosition(
                    checkpoint.lastAppliedAeronPosition(),
                    recordingStart,
                    replayStop);
                final long replayStart = checkpoint.lastAppliedAeronPosition();
                final ReplayFragmentHandler handler = new ReplayFragmentHandler(
                    state,
                    checkpoints,
                    command.checkpointKey(),
                    command.recordingId(),
                    properties.getCheckpointEveryProcessedMessages(),
                    replayStart,
                    replayStop,
                    progressListener);
                // Make the initial/resume Position a real persisted recovery point
                // before it is exposed as lastCheckpointPosition.
                handler.save();

                replayRange(
                    aeron,
                    archive,
                    command.recordingId(),
                    replayStart,
                    replayStop,
                    state,
                    handler);
                handler.save();

                final boolean passed =
                    state.lastAppliedAeronPosition() == replayStop &&
                    command.expectedLastEventSequence() ==
                        state.lastAppliedEventSequence() &&
                    command.expectedReplayDigest() == state.replayDigest();
                final ReplayResult result = new ReplayResult(
                    command.recordingId(),
                    command.checkpointKey(),
                    replayStart,
                    replayStop,
                    handler.firstAppliedSequenceThisRun(),
                    handler.lastAppliedSequenceThisRun(),
                    state.lastAppliedEventSequence(),
                    command.expectedLastEventSequence(),
                    handler.appliedEventsThisRun(),
                    state.appliedEventsTotal(),
                    handler.duplicatesThisRun(),
                    state.duplicatesTotal(),
                    state.sequenceGapsThisRun(),
                    state.replayDigest(),
                    command.expectedReplayDigest(),
                    Duration.ofNanos(System.nanoTime() - startedNs).toMillis(),
                    passed);
                if (passed)
                {
                    completionProofs.save(new CompletionProof(
                        command.checkpointKey(),
                        command.recordingId(),
                        replayStart,
                        replayStop,
                        state.lastAppliedEventSequence(),
                        state.replayDigest(),
                        CompletionVerificationStatus.VERIFIED,
                        Instant.now()));
                }
                return result;
            }
            catch (final ReplayException ex)
            {
                throw ex.withReplayContext(
                    command.recordingId(),
                    state.lastAppliedAeronPosition(),
                    replayStop,
                    state.lastAppliedEventSequence());
            }
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
                    throw new ReplayException(
                        ReplayFailure.basic(
                            ReplayFailureCode.CHECKPOINT_RECORDING_MISMATCH,
                            "Checkpoint recordingId=" + checkpoint.recordingId() +
                                " does not match requested recordingId=" +
                                command.recordingId())
                            .withReplayContext(
                                command.recordingId(),
                                checkpoint.lastAppliedAeronPosition(),
                                checkpoint.lastAppliedAeronPosition(),
                                checkpoint.lastAppliedEventSequence()));
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

        final long replaySessionId;
        try
        {
            replaySessionId = archive.startReplay(
                recordingId,
                replayStart,
                replayLength,
                properties.getReplayChannel(),
                properties.getReplayStreamId());
        }
        catch (final RuntimeException ex)
        {
            throw new ReplayException(
                ReplayFailure.basic(
                    ReplayFailureCode.REPLAY_IMAGE_UNAVAILABLE,
                    "Unable to start Archive replay for recordingId=" + recordingId),
                ex);
        }

        final String replaySessionChannel = ChannelUri.addSessionId(
            properties.getReplayChannel(),
            (int)replaySessionId);
        RuntimeException replayFailure = null;
        try (Subscription subscription = aeron.addSubscription(
            replaySessionChannel,
            properties.getReplayStreamId()))
        {
            try
            {
                pollReplay(subscription, state, handler, recordingId, replayStop);
            }
            catch (final RuntimeException ex)
            {
                replayFailure = ex;
                throw ex;
            }
        }
        finally
        {
            try
            {
                archive.stopReplay(replaySessionId);
            }
            catch (final RuntimeException stopFailure)
            {
                if (replayFailure == null)
                {
                    throw stopFailure;
                }
                replayFailure.addSuppressed(stopFailure);
            }
        }
    }

    private void pollReplay(
        final Subscription subscription,
        final ProjectionState state,
        final ReplayFragmentHandler handler,
        final long recordingId,
        final long replayStop)
    {
        final FragmentAssembler assembler = new FragmentAssembler(handler);
        final IdleStrategy idle = new BackoffIdleStrategy();
        final NoProgressWatchdog watchdog = new NoProgressWatchdog(
            properties.getNoProgressTimeout(),
            properties.getMaximumReplayDuration(),
            state.lastAppliedAeronPosition());
        while (state.lastAppliedAeronPosition() < replayStop)
        {
            final int fragments = subscription.poll(
                assembler,
                properties.getFragmentLimit());
            handler.throwIfFailed();
            idle.idle(fragments);
            watchdog.check(
                recordingId,
                state.lastAppliedAeronPosition(),
                replayStop,
                state.lastAppliedEventSequence());
        }
    }

    private static long availablePosition(
        final AeronArchive archive,
        final long recordingId)
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
            throw new ReplayException(ReplayFailure.basic(
                ReplayFailureCode.INVALID_REPLAY_RANGE,
                "Checkpoint position=" + checkpointPosition +
                    " is outside replay range=[" + recordingStart +
                    ", " + replayStop + "]"));
        }
    }
}
