package io.github.mikuwwl.matchingreplay.orderbook;

import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.Hashing;
import io.github.mikuwwl.matchingreplay.domain.LimitOrderCommand;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Order;
import io.github.mikuwwl.matchingreplay.domain.Side;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class OrderBook
{
    public static final short SCHEMA_VERSION = 1;

    private final int symbolId;
    private final Thread ownerThread;
    private final NavigableMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();

    private long nextPrioritySequence = 1;
    private long nextEventSequence = 1;
    private long nextTradeId = 1;

    public OrderBook(final int symbolId)
    {
        if (symbolId <= 0)
        {
            throw new IllegalArgumentException("symbolId must be positive");
        }
        this.symbolId = symbolId;
        this.ownerThread = Thread.currentThread();
    }

    public List<MatchingEvent> submit(final LimitOrderCommand command)
    {
        assertOwnerThread();
        if (command.symbolId() != symbolId)
        {
            throw new IllegalArgumentException(
                "OrderBook symbol is " + symbolId + ", command symbol is " + command.symbolId());
        }

        final Order incoming = new Order(
            command.orderId(),
            command.symbolId(),
            command.side(),
            command.price(),
            command.quantity(),
            nextPrioritySequence++);
        final List<MatchingEvent> events = new ArrayList<>();
        events.add(event(
            EventType.ORDER_ACCEPTED,
            command,
            0,
            0,
            command.price(),
            command.quantity(),
            command.quantity()));

        boolean traded = false;
        while (incoming.remainingQuantity() > 0 && crosses(incoming))
        {
            final NavigableMap<Long, ArrayDeque<Order>> opposite = incoming.side() == Side.BUY ? asks : bids;
            final Map.Entry<Long, ArrayDeque<Order>> level = opposite.firstEntry();
            final ArrayDeque<Order> queue = level.getValue();
            final Order resting = queue.peekFirst();
            final long tradeQuantity = Math.min(incoming.remainingQuantity(), resting.remainingQuantity());
            final long tradePrice = resting.price();
            final long tradeId = nextTradeId++;

            incoming.fill(tradeQuantity);
            resting.fill(tradeQuantity);
            traded = true;

            events.add(event(
                EventType.TRADE_EXECUTED,
                command,
                resting.orderId(),
                tradeId,
                tradePrice,
                tradeQuantity,
                incoming.remainingQuantity()));

            if (resting.remainingQuantity() == 0)
            {
                queue.removeFirst();
                if (queue.isEmpty())
                {
                    opposite.remove(level.getKey());
                }
            }
        }

        if (incoming.remainingQuantity() > 0)
        {
            final NavigableMap<Long, ArrayDeque<Order>> sameSide = incoming.side() == Side.BUY ? bids : asks;
            sameSide.computeIfAbsent(incoming.price(), ignored -> new ArrayDeque<>()).addLast(incoming);
        }

        if (traded)
        {
            events.add(event(
                incoming.remainingQuantity() == 0 ? EventType.ORDER_FILLED : EventType.ORDER_PARTIALLY_FILLED,
                command,
                0,
                0,
                command.price(),
                command.quantity() - incoming.remainingQuantity(),
                incoming.remainingQuantity()));
        }

        return List.copyOf(events);
    }

    private boolean crosses(final Order incoming)
    {
        if (incoming.side() == Side.BUY)
        {
            return !asks.isEmpty() && asks.firstKey() <= incoming.price();
        }
        return !bids.isEmpty() && bids.firstKey() >= incoming.price();
    }

    private MatchingEvent event(
        final EventType eventType,
        final LimitOrderCommand command,
        final long contraOrderId,
        final long tradeId,
        final long price,
        final long quantity,
        final long remainingQuantity)
    {
        return new MatchingEvent(
            SCHEMA_VERSION,
            eventType,
            nextEventSequence++,
            command.receivedTimestampNs(),
            command.orderId(),
            contraOrderId,
            tradeId,
            command.symbolId(),
            command.side(),
            price,
            quantity,
            remainingQuantity);
    }

    public long stateHash()
    {
        assertOwnerThread();
        long hash = Hashing.FNV_OFFSET_BASIS;
        hash = hashSide(hash, bids);
        return hashSide(hash, asks);
    }

    private static long hashSide(long hash, final NavigableMap<Long, ArrayDeque<Order>> levels)
    {
        for (final Map.Entry<Long, ArrayDeque<Order>> level : levels.entrySet())
        {
            hash = Hashing.mix(hash, level.getKey());
            for (final Order order : level.getValue())
            {
                hash = Hashing.mix(hash, order.orderId());
                hash = Hashing.mix(hash, order.side().code());
                hash = Hashing.mix(hash, order.remainingQuantity());
                hash = Hashing.mix(hash, order.prioritySequence());
            }
        }
        return hash;
    }

    public long lastEventSequence()
    {
        return nextEventSequence - 1;
    }

    public long lastTradeId()
    {
        return nextTradeId - 1;
    }

    public int restingOrderCount()
    {
        return bids.values().stream().mapToInt(ArrayDeque::size).sum() +
            asks.values().stream().mapToInt(ArrayDeque::size).sum();
    }

    private void assertOwnerThread()
    {
        if (Thread.currentThread() != ownerThread)
        {
            throw new IllegalStateException("OrderBook may only be mutated/read by its owning engine thread");
        }
    }
}
