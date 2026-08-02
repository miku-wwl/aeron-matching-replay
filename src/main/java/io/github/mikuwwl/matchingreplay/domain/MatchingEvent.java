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
    long remainingQuantity,
    int sourceId)
{
    public MatchingEvent(
        final short schemaVersion,
        final EventType eventType,
        final long eventSequence,
        final long timestampNs,
        final long orderId,
        final long contraOrderId,
        final long tradeId,
        final int symbolId,
        final Side side,
        final long price,
        final long quantity,
        final long remainingQuantity)
    {
        this(
            schemaVersion,
            eventType,
            eventSequence,
            timestampNs,
            orderId,
            contraOrderId,
            tradeId,
            symbolId,
            side,
            price,
            quantity,
            remainingQuantity,
            0);
    }

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
            remainingQuantity < 0 || contraOrderId < 0 || tradeId < 0 || sourceId < 0)
        {
            throw new IllegalArgumentException("Invalid matching event values");
        }
        Objects.requireNonNull(side, "side");
    }
}
