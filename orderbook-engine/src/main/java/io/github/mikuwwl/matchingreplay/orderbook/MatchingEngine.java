package io.github.mikuwwl.matchingreplay.orderbook;

import io.github.mikuwwl.matchingreplay.domain.LimitOrderCommand;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;

import java.util.List;
import java.util.Objects;

public final class MatchingEngine
{
    private final OrderBook orderBook;
    private final MatchingEventPublisher publisher;
    private long ordersSubmitted;
    private long eventsPublished;

    public MatchingEngine(final OrderBook orderBook, final MatchingEventPublisher publisher)
    {
        this.orderBook = Objects.requireNonNull(orderBook, "orderBook");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public List<MatchingEvent> submit(final LimitOrderCommand command)
    {
        final List<MatchingEvent> events = orderBook.submit(command);
        for (final MatchingEvent event : events)
        {
            publisher.publish(event);
            eventsPublished++;
        }
        ordersSubmitted++;
        return events;
    }

    public long ordersSubmitted()
    {
        return ordersSubmitted;
    }

    public long eventsPublished()
    {
        return eventsPublished;
    }

    public OrderBook orderBook()
    {
        return orderBook;
    }
}
