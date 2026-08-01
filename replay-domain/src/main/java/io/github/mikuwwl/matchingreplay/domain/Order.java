package io.github.mikuwwl.matchingreplay.domain;

import java.util.Objects;

public final class Order
{
    private final long orderId;
    private final int symbolId;
    private final Side side;
    private final long price;
    private final long originalQuantity;
    private final long prioritySequence;

    private long remainingQuantity;
    private OrderStatus status;

    public Order(
        final long orderId,
        final int symbolId,
        final Side side,
        final long price,
        final long quantity,
        final long prioritySequence)
    {
        if (orderId <= 0)
        {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (symbolId <= 0)
        {
            throw new IllegalArgumentException("symbolId must be positive");
        }
        this.side = Objects.requireNonNull(side, "side");
        if (price <= 0)
        {
            throw new IllegalArgumentException("price must be positive");
        }
        if (quantity <= 0)
        {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (prioritySequence <= 0)
        {
            throw new IllegalArgumentException("prioritySequence must be positive");
        }

        this.orderId = orderId;
        this.symbolId = symbolId;
        this.price = price;
        this.originalQuantity = quantity;
        this.prioritySequence = prioritySequence;
        this.remainingQuantity = quantity;
        this.status = OrderStatus.NEW;
    }

    public void fill(final long quantity)
    {
        if (status == OrderStatus.FILLED || status == OrderStatus.CANCELLED)
        {
            throw new IllegalStateException("Cannot fill order in state " + status);
        }
        if (quantity <= 0 || quantity > remainingQuantity)
        {
            throw new IllegalArgumentException("Invalid fill quantity: " + quantity);
        }

        remainingQuantity = Math.subtractExact(remainingQuantity, quantity);
        status = remainingQuantity == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel()
    {
        if (status == OrderStatus.FILLED || status == OrderStatus.CANCELLED)
        {
            throw new IllegalStateException("Cannot cancel order in state " + status);
        }
        status = OrderStatus.CANCELLED;
    }

    public long orderId()
    {
        return orderId;
    }

    public int symbolId()
    {
        return symbolId;
    }

    public Side side()
    {
        return side;
    }

    public long price()
    {
        return price;
    }

    public long originalQuantity()
    {
        return originalQuantity;
    }

    public long prioritySequence()
    {
        return prioritySequence;
    }

    public long remainingQuantity()
    {
        return remainingQuantity;
    }

    public OrderStatus status()
    {
        return status;
    }
}
