package io.github.mikuwwl.matchingreplay.domain;

import java.util.Objects;

public record LimitOrderCommand(
    long orderId,
    int symbolId,
    Side side,
    long price,
    long quantity,
    long receivedTimestampNs)
{
    public LimitOrderCommand
    {
        if (orderId <= 0)
        {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (symbolId <= 0)
        {
            throw new IllegalArgumentException("symbolId must be positive");
        }
        Objects.requireNonNull(side, "side");
        if (price <= 0)
        {
            throw new IllegalArgumentException("price must be positive");
        }
        if (quantity <= 0)
        {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (receivedTimestampNs < 0)
        {
            throw new IllegalArgumentException("receivedTimestampNs must not be negative");
        }
    }
}
