package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.github.mikuwwl.matchingreplay.codec.MatchingEventSbeEncoder;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.time.Duration;

public final class AeronMatchingEventPublisher
{
    private final ExclusivePublication publication;
    private final MatchingEventSbeEncoder encoder = new MatchingEventSbeEncoder();
    private final UnsafeBuffer sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
    private final IdleStrategy idleStrategy = new BackoffIdleStrategy();
    private final long offerTimeoutNs;
    private long backPressureCount;
    private long eventsPublished;

    public AeronMatchingEventPublisher(
        final ExclusivePublication publication,
        final Duration offerTimeout)
    {
        this.publication = publication;
        this.offerTimeoutNs = offerTimeout.toNanos();
    }

    public long publish(final MatchingEvent event)
    {
        final int encodedLength = encoder.encode(event, sendBuffer, 0);
        final long deadline = System.nanoTime() + offerTimeoutNs;
        long result;
        while ((result = publication.offer(sendBuffer, 0, encodedLength)) < 0)
        {
            if (result == Publication.BACK_PRESSURED)
            {
                backPressureCount++;
            }
            else if (result == Publication.CLOSED)
            {
                throw new IllegalStateException("Publication is closed");
            }
            else if (result == Publication.MAX_POSITION_EXCEEDED)
            {
                throw new IllegalStateException("Publication max position exceeded");
            }
            else if (result != Publication.ADMIN_ACTION && result != Publication.NOT_CONNECTED)
            {
                throw new IllegalStateException("Unknown Publication.offer result: " + result);
            }

            if (System.nanoTime() >= deadline)
            {
                throw new IllegalStateException(
                    "Timed out offering eventSequence=" + event.eventSequence() + ", result=" + result);
            }
            idleStrategy.idle();
        }
        idleStrategy.reset();
        eventsPublished++;
        return result;
    }

    public long backPressureCount()
    {
        return backPressureCount;
    }

    public long eventsPublished()
    {
        return eventsPublished;
    }
}
