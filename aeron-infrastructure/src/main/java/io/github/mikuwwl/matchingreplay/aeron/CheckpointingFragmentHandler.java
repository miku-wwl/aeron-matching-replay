package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.github.mikuwwl.matchingreplay.codec.MatchingEventSbeDispatcher;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import org.agrona.DirectBuffer;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class CheckpointingFragmentHandler implements FragmentHandler
{
    private final MatchingEventSbeDispatcher dispatcher = new MatchingEventSbeDispatcher();
    private final ProjectionState state;
    private final CheckpointStore checkpointStore;
    private final String consumerName;
    private final LongSupplier recordingIdSupplier;
    private final int checkpointEvery;
    private final long crashAfterSequence;
    private final Consumer<Checkpoint> crashAction;

    private long recordingId = -1;
    private long fragmentsProcessed;
    private long firstAppliedSequence;
    private long lastAppliedSequence;

    public CheckpointingFragmentHandler(
        final ProjectionState state,
        final CheckpointStore checkpointStore,
        final String consumerName,
        final LongSupplier recordingIdSupplier,
        final int checkpointEvery,
        final long crashAfterSequence,
        final Consumer<Checkpoint> crashAction)
    {
        if (checkpointEvery <= 0 || crashAfterSequence < 0)
        {
            throw new IllegalArgumentException("Invalid checkpoint/crash configuration");
        }
        this.state = Objects.requireNonNull(state, "state");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.consumerName = Objects.requireNonNull(consumerName, "consumerName");
        this.recordingIdSupplier = Objects.requireNonNull(recordingIdSupplier, "recordingIdSupplier");
        this.checkpointEvery = checkpointEvery;
        this.crashAfterSequence = crashAfterSequence;
        this.crashAction = Objects.requireNonNull(crashAction, "crashAction");
    }

    @Override
    public void onFragment(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header)
    {
        if (recordingId < 0)
        {
            recordingId = recordingIdSupplier.getAsLong();
            if (recordingId < 0)
            {
                throw new IllegalStateException("Invalid recordingId: " + recordingId);
            }
        }

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

        final boolean crashNow = crashAfterSequence > 0 &&
            event.eventSequence() == crashAfterSequence &&
            result == ProjectionState.ApplyResult.APPLIED;
        if (crashNow || fragmentsProcessed % checkpointEvery == 0)
        {
            final Checkpoint checkpoint = state.checkpoint(consumerName, recordingId);
            checkpointStore.write(checkpoint);
            if (crashNow)
            {
                crashAction.accept(checkpoint);
            }
        }
    }

    public void save()
    {
        if (recordingId < 0)
        {
            recordingId = recordingIdSupplier.getAsLong();
        }
        checkpointStore.write(state.checkpoint(consumerName, recordingId));
    }

    public long firstAppliedSequence()
    {
        return firstAppliedSequence;
    }

    public long lastAppliedSequence()
    {
        return lastAppliedSequence;
    }
}
