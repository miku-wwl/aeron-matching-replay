package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.codec.MatchingEventSbeDispatcher;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.projection.ProjectionState;
import org.agrona.DirectBuffer;

final class ReplayFragmentHandler implements FragmentHandler
{
    private final MatchingEventSbeDispatcher dispatcher = new MatchingEventSbeDispatcher();
    private final ProjectionState state;
    private final CheckpointRepository checkpoints;
    private final String checkpointKey;
    private final long recordingId;
    private final int checkpointEvery;

    private long fragmentsProcessed;
    private long firstAppliedSequence;
    private long lastAppliedSequence;

    ReplayFragmentHandler(
        final ProjectionState state,
        final CheckpointRepository checkpoints,
        final String checkpointKey,
        final long recordingId,
        final int checkpointEvery)
    {
        this.state = state;
        this.checkpoints = checkpoints;
        this.checkpointKey = checkpointKey;
        this.recordingId = recordingId;
        this.checkpointEvery = checkpointEvery;
    }

    @Override
    public void onFragment(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header)
    {
        final MatchingEvent event = dispatcher.decode(buffer, offset, length);
        final ProjectionState.ApplyResult result = state.apply(event, header.position());
        fragmentsProcessed++;
        if (result == ProjectionState.ApplyResult.APPLIED)
        {
            if (firstAppliedSequence == 0)
            {
                firstAppliedSequence = event.eventSequence();
            }
            lastAppliedSequence = event.eventSequence();
        }

        if (fragmentsProcessed % checkpointEvery == 0)
        {
            save();
        }
    }

    void save()
    {
        checkpoints.save(state.checkpoint(checkpointKey, recordingId));
    }

    long firstAppliedSequence()
    {
        return firstAppliedSequence;
    }

    long lastAppliedSequence()
    {
        return lastAppliedSequence;
    }
}
