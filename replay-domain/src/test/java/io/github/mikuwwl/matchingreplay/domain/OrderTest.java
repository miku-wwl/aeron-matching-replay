package io.github.mikuwwl.matchingreplay.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest
{
    @Test
    void rejectsInvalidPriceAndQuantity()
    {
        assertThrows(IllegalArgumentException.class, () -> new Order(1, 1, Side.BUY, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Order(1, 1, Side.BUY, 1, 0, 1));
    }

    @Test
    void enforcesLegalStateTransitions()
    {
        final Order order = new Order(1, 1, Side.BUY, 100, 10, 1);
        order.fill(4);
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.status());
        assertEquals(6, order.remainingQuantity());
        order.fill(6);
        assertEquals(OrderStatus.FILLED, order.status());
        assertThrows(IllegalStateException.class, () -> order.fill(1));
        assertThrows(IllegalStateException.class, order::cancel);
    }

    @Test
    void preventsArithmeticAndQuantityErrors()
    {
        final Order order = new Order(1, 1, Side.SELL, Long.MAX_VALUE, Long.MAX_VALUE, 1);
        order.fill(Long.MAX_VALUE - 1);
        assertEquals(1, order.remainingQuantity());
        assertThrows(IllegalArgumentException.class, () -> order.fill(2));
    }
}
