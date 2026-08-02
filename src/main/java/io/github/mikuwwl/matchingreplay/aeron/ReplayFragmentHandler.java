package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.codec.MatchingEventSbeDispatcher;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import io.github.mikuwwl.matchingreplay.projection.ProjectionState;
import org.agrona.DirectBuffer;

import java.time.Instant;

final class ReplayFragmentHandler implements FragmentHandler
{
    private final MatchingEventSbeDispatcher dispatcher = new MatchingEventSbeDispatcher();
    private final ProjectionState state;
    private final CheckpointRepository checkpoints;
    private final String checkpointKey;
    private final long recordingId;
    private final int checkpointEveryProcessedMessages;
    private final long replayStartPosition;
    private final long replayStopPosition;
    private final ReplayProgressListener progressListener;
    private final long initialAppliedEventsTotal;
    private final long initialDuplicatesTotal;
    private final long startedNs = System.nanoTime();

    private long processedMessagesThisRun;
    private long firstAppliedSequenceThisRun;
    private long lastAppliedSequenceThisRun;
    private long lastCheckpointPosition;
    private Instant lastProgressAt = Instant.now();
    private RuntimeException failure;

    ReplayFragmentHandler(
        final ProjectionState state,
        final CheckpointRepository checkpoints,
        final String checkpointKey,
        final long recordingId,
        final int checkpointEveryProcessedMessages,
        final long replayStartPosition,
        final long replayStopPosition,
        final ReplayProgressListener progressListener)
    {
        this.state = state;
        this.checkpoints = checkpoints;
        this.checkpointKey = checkpointKey;
        this.recordingId = recordingId;
        this.checkpointEveryProcessedMessages = checkpointEveryProcessedMessages;
        this.replayStartPosition = replayStartPosition;
        this.replayStopPosition = replayStopPosition;
        this.progressListener = progressListener;
        initialAppliedEventsTotal = state.appliedEventsTotal();
        initialDuplicatesTotal = state.duplicatesTotal();
        lastCheckpointPosition = replayStartPosition;
    }

    @Override
    public void onFragment(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header)
    {
        if (failure != null)
        {
            return;
        }

        try
        {
            if (header.position() > replayStopPosition)
            {
                throw new ReplayException(ReplayFailure.basic(
                    ReplayFailureCode.INVALID_REPLAY_RANGE,
                    "Fragment end position=" + header.position() +
                        " exceeds replayStopPosition=" + replayStopPosition));
            }

            final MatchingEvent event = dispatcher.decode(buffer, offset, length);
            final ProjectionState.ApplyResult result = state.apply(event, header.position());
            processedMessagesThisRun++;
            lastProgressAt = Instant.now();
            if (result == ProjectionState.ApplyResult.APPLIED)
            {
                if (firstAppliedSequenceThisRun == 0)
                {
                    firstAppliedSequenceThisRun = event.eventSequence();
                }
                lastAppliedSequenceThisRun = event.eventSequence();
            }

            if (processedMessagesThisRun % checkpointEveryProcessedMessages == 0)
            {
                save();
            }
            else
            {
                publishProgress();
            }
        }
        catch (final RuntimeException ex)
        {
            failure = ex;
        }
    }

    void throwIfFailed()
    {
        if (failure != null)
        {
            throw failure;
        }
    }

    void save()
    {
        checkpoints.save(state.checkpoint(checkpointKey, recordingId));
        lastCheckpointPosition = state.lastAppliedAeronPosition();
        publishProgress();
    }

    long firstAppliedSequenceThisRun()
    {
        return firstAppliedSequenceThisRun;
    }

    long lastAppliedSequenceThisRun()
    {
        return lastAppliedSequenceThisRun;
    }

    long appliedEventsThisRun()
    {
        return state.appliedEventsTotal() - initialAppliedEventsTotal;
    }

    long duplicatesThisRun()
    {
        return state.duplicatesTotal() - initialDuplicatesTotal;
    }

    private void publishProgress()
    {
        progressListener.onProgress(ReplayProgress.snapshot(
            replayStartPosition,
            state.lastAppliedAeronPosition(),
            replayStopPosition,
            state.lastAppliedEventSequence(),
            appliedEventsThisRun(),
            duplicatesThisRun(),
            lastCheckpointPosition,
            eventsPerSecond(),
            lastProgressAt));
    }

    private long eventsPerSecond()
    {
        if (processedMessagesThisRun == 0)
        {
            return 0;
        }
        final long elapsedNs = Math.max(1, System.nanoTime() - startedNs);
        return (long)(processedMessagesThisRun * 1_000_000_000.0 / elapsedNs);
    }
}
