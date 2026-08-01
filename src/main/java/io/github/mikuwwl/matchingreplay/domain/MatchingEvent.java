package io.github.mikuwwl.matchingreplay.domain;

import java.util.Objects;

public record MatchingEvent(
    short schemaVersion,
    EventType eventType,
    long eventSequence,
    long timestampNs,
    long orderId,
    long contraOrderId,
    long tradeId,
    int symbolId,
    Side side,
    long price,
    long quantity,
    long remainingQuantity)
{
    public MatchingEvent
    {
        if (schemaVersion <= 0)
        {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(eventType, "eventType");
        if (eventSequence <= 0)
        {
            throw new IllegalArgumentException("eventSequence must be positive");
        }
        if (timestampNs < 0 || orderId <= 0 || symbolId <= 0 || price <= 0 || quantity <= 0 ||
            remainingQuantity < 0 || contraOrderId < 0 || tradeId < 0)
        {
            throw new IllegalArgumentException("Invalid matching event values");
        }
        Objects.requireNonNull(side, "side");
    }
}
