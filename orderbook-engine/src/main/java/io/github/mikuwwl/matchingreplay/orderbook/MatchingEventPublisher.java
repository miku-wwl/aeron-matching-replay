package io.github.mikuwwl.matchingreplay.orderbook;

import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;

@FunctionalInterface
public interface MatchingEventPublisher
{
    long publish(MatchingEvent event);
}
