package io.github.mikuwwl.matchingreplay.orderbook;

import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.LimitOrderCommand;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookTest
{
    @Test
    void usesBestPriceThenTimePriority()
    {
        final OrderBook book = new OrderBook(1);
        book.submit(command(1, Side.SELL, 102, 5));
        book.submit(command(2, Side.SELL, 101, 5));
        book.submit(command(3, Side.SELL, 101, 5));

        final List<MatchingEvent> events = book.submit(command(4, Side.BUY, 102, 8));
        final List<MatchingEvent> trades = trades(events);

        assertEquals(List.of(2L, 3L), trades.stream().map(MatchingEvent::contraOrderId).toList());
        assertEquals(List.of(101L, 101L), trades.stream().map(MatchingEvent::price).toList());
        assertEquals(List.of(5L, 3L), trades.stream().map(MatchingEvent::quantity).toList());
    }

    @Test
    void usesHighestBidAndSupportsMultiLevelFullFill()
    {
        final OrderBook book = new OrderBook(1);
        book.submit(command(1, Side.BUY, 99, 4));
        book.submit(command(2, Side.BUY, 101, 3));
        book.submit(command(3, Side.BUY, 100, 5));

        final List<MatchingEvent> events = book.submit(command(4, Side.SELL, 99, 8));
        final List<MatchingEvent> trades = trades(events);

        assertEquals(List.of(2L, 3L), trades.stream().map(MatchingEvent::contraOrderId).toList());
        assertEquals(List.of(101L, 100L), trades.stream().map(MatchingEvent::price).toList());
        assertEquals(EventType.ORDER_FILLED, events.getLast().eventType());
        assertEquals(2, book.lastTradeId());
    }

    @Test
    void handlesPartialFillAndNonCrossingOrder()
    {
        final OrderBook book = new OrderBook(1);
        assertEquals(1, book.submit(command(1, Side.SELL, 105, 3)).size());
        assertEquals(1, book.submit(command(2, Side.BUY, 104, 4)).size());

        final List<MatchingEvent> events = book.submit(command(3, Side.BUY, 105, 5));
        assertEquals(EventType.TRADE_EXECUTED, events.get(1).eventType());
        assertEquals(EventType.ORDER_PARTIALLY_FILLED, events.getLast().eventType());
        assertEquals(2, events.getLast().remainingQuantity());
    }

    @Test
    void eventAndTradeSequencesAreStrictlyContinuous()
    {
        final OrderBook book = new OrderBook(1);
        final List<MatchingEvent> all = new ArrayList<>();
        for (final LimitOrderCommand command : SyntheticOrderFeed.generate(100, 42, 1))
        {
            all.addAll(book.submit(command));
        }

        for (int index = 0; index < all.size(); index++)
        {
            assertEquals(index + 1L, all.get(index).eventSequence());
        }
        final List<Long> tradeIds = all.stream()
            .filter(event -> event.eventType() == EventType.TRADE_EXECUTED)
            .map(MatchingEvent::tradeId)
            .toList();
        for (int index = 0; index < tradeIds.size(); index++)
        {
            assertEquals(index + 1L, tradeIds.get(index));
        }
        assertTrue(tradeIds.size() > 20);
    }

    @Test
    void deterministicFeedProducesSameEventsAndBookHash()
    {
        final Run first = run(500, 20210801);
        final Run second = run(500, 20210801);
        final Run different = run(500, 20210802);

        assertEquals(first.events, second.events);
        assertEquals(first.hash, second.hash);
        assertNotEquals(first.events, different.events);
    }

    @Test
    void rejectsWrongSymbolAndCrossThreadAccess() throws InterruptedException
    {
        final OrderBook book = new OrderBook(1);
        assertThrows(IllegalArgumentException.class, () ->
            book.submit(new LimitOrderCommand(1, 2, Side.BUY, 100, 1, 1)));

        final List<Throwable> failures = new ArrayList<>();
        final Thread thread = new Thread(() ->
        {
            try
            {
                book.submit(command(2, Side.BUY, 100, 1));
            }
            catch (final Throwable ex)
            {
                failures.add(ex);
            }
        });
        thread.start();
        thread.join();
        assertTrue(failures.getFirst() instanceof IllegalStateException);
    }

    private static List<MatchingEvent> trades(final List<MatchingEvent> events)
    {
        return events.stream().filter(event -> event.eventType() == EventType.TRADE_EXECUTED).toList();
    }

    private static LimitOrderCommand command(
        final long orderId,
        final Side side,
        final long price,
        final long quantity)
    {
        return new LimitOrderCommand(orderId, 1, side, price, quantity, orderId * 1_000);
    }

    private static Run run(final int count, final long seed)
    {
        final OrderBook book = new OrderBook(1);
        final List<MatchingEvent> events = new ArrayList<>();
        for (final LimitOrderCommand command : SyntheticOrderFeed.generate(count, seed, 1))
        {
            events.addAll(book.submit(command));
        }
        return new Run(events, book.stateHash());
    }

    private record Run(List<MatchingEvent> events, long hash)
    {
    }
}
