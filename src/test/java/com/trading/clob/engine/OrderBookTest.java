package com.trading.clob.engine;

import com.trading.clob.domain.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {
    private static final Instrument INSTRUMENT = new Instrument(new Asset("BTC"), new Asset("BRL"));

    private static Order order(long id, OrderSide side, String price, long sequence) {
        return new Order(id, "account", INSTRUMENT, side, new BigDecimal(price), BigDecimal.ONE, sequence);
    }

    @Test
    void bestBidUsesHighestPriceAndBestAskUsesLowestPrice() {
        OrderBook book = new OrderBook(INSTRUMENT);
        Order highBid = order(1, OrderSide.BUY, "510", 1);
        Order lowAsk = order(4, OrderSide.SELL, "520", 4);
        book.add(highBid);
        book.add(order(2, OrderSide.BUY, "500", 2));
        book.add(order(3, OrderSide.SELL, "530", 3));
        book.add(lowAsk);
        assertSame(highBid, book.bestBid());
        assertSame(lowAsk, book.bestAsk());
        assertEquals(INSTRUMENT, book.instrument());
    }

    @Test
    void samePriceIsFifoOnBothSides() {
        for (OrderSide side : OrderSide.values()) {
            OrderBook book = new OrderBook(INSTRUMENT);
            Order first = order(1, side, "500", 1);
            Order second = order(2, side, "500", 2);
            book.add(first);
            book.add(second);
            assertSame(first, side == OrderSide.BUY ? book.bestBid() : book.bestAsk());
            book.remove(first.id());
            assertSame(second, side == OrderSide.BUY ? book.bestBid() : book.bestAsk());
        }
    }

    @Test
    void equalNumericPricesSharePriorityWithoutChangingStoredScale() {
        for (OrderSide side : OrderSide.values()) {
            OrderBook book = new OrderBook(INSTRUMENT);
            Order later = order(2, side, "500.0", 2);
            Order earlier = order(1, side, "500.00", 1);
            book.add(earlier);
            book.add(later);
            assertSame(earlier, side == OrderSide.BUY ? book.bestBid() : book.bestAsk());
            assertEquals(new BigDecimal("500.0"), later.limitPrice());
            assertEquals(new BigDecimal("500.00"), earlier.limitPrice());
            book.remove(earlier.id());
            assertSame(later, side == OrderSide.BUY ? book.bestBid() : book.bestAsk());
            book.remove(later.id());
            assertNull(side == OrderSide.BUY ? book.bestBid() : book.bestAsk());
        }
    }

    @Test
    void lookupAndRemovalHandleNonHeadOrdersWithoutCancelling() {
        OrderBook book = new OrderBook(INSTRUMENT);
        Order first = order(1, OrderSide.BUY, "500", 1);
        Order second = order(2, OrderSide.BUY, "500", 2);
        book.add(first);
        book.add(second);
        assertSame(second, book.orderById(2));
        assertSame(second, book.remove(2));
        assertNull(book.orderById(2));
        assertSame(first, book.bestBid());
        assertEquals(OrderStatus.OPEN, second.status());
        assertEquals(BigDecimal.ONE, second.remainingQuantity());
        assertNull(book.orderById(999));
        assertNull(book.remove(999));
    }

    @Test
    void removingLastOrderAtLevelExposesNextLevelAndThenEmptySide() {
        for (OrderSide side : OrderSide.values()) {
            OrderBook book = new OrderBook(INSTRUMENT);
            Order first = order(1, side, side == OrderSide.BUY ? "510" : "490", 1);
            Order second = order(2, side, "500", 2);
            book.add(first);
            book.add(second);
            book.remove(1);
            assertSame(second, side == OrderSide.BUY ? book.bestBid() : book.bestAsk());
            book.remove(2);
            assertNull(side == OrderSide.BUY ? book.bestBid() : book.bestAsk());
            book.add(first);
            assertSame(first, side == OrderSide.BUY ? book.bestBid() : book.bestAsk());
        }
    }

    @Test
    void wrongInstrumentAndNullAreRejectedWithoutMutation() {
        OrderBook book = new OrderBook(INSTRUMENT);
        Instrument other = new Instrument(new Asset("ETH"), new Asset("BRL"));
        Order wrong = new Order(1, "account", other, OrderSide.BUY, BigDecimal.ONE, BigDecimal.ONE, 1);
        assertThrows(IllegalArgumentException.class, () -> book.add(wrong));
        assertThrows(NullPointerException.class, () -> book.add(null));
        assertThrows(NullPointerException.class, () -> new OrderBook(null));
        assertNull(book.orderById(1));
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
    }

    @Test
    void filledAndCancelledOrdersAreRejected() {
        OrderBook book = new OrderBook(INSTRUMENT);
        Order filled = order(1, OrderSide.BUY, "500", 1);
        filled.applyFill(BigDecimal.ONE);
        Order cancelled = order(2, OrderSide.SELL, "500", 2);
        cancelled.cancel();
        assertThrows(IllegalArgumentException.class, () -> book.add(filled));
        assertThrows(IllegalArgumentException.class, () -> book.add(cancelled));
        assertNull(book.orderById(1));
        assertNull(book.orderById(2));
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
    }

    @Test
    void partialFillsPreserveQueuePositionAndOriginalPriority() {
        OrderBook book = new OrderBook(INSTRUMENT);
        Order first = order(1, OrderSide.SELL, "500", 1);
        Order second = order(2, OrderSide.SELL, "500", 2);
        first.applyFill(new BigDecimal("0.2"));
        book.add(first);
        book.add(second);
        first.applyFill(new BigDecimal("0.3"));
        assertSame(first, book.bestAsk());
        assertSame(first, book.orderById(1));
        assertEquals(new BigDecimal("0.5"), first.remainingQuantity());
        assertEquals(1, first.sequence());
        book.remove(1);
        assertSame(second, book.bestAsk());
    }

    @Test
    void sidesRemainIndependentEvenAtCrossingPrices() {
        OrderBook book = new OrderBook(INSTRUMENT);
        Order bid = order(1, OrderSide.BUY, "500", 1);
        Order ask = order(2, OrderSide.SELL, "500", 2);
        book.add(bid);
        book.add(ask);
        assertSame(bid, book.bestBid());
        assertSame(ask, book.bestAsk());
        book.remove(1);
        assertNull(book.bestBid());
        assertSame(ask, book.bestAsk());
        assertEquals(BigDecimal.ONE, ask.remainingQuantity());
    }

    @Test
    void duplicateIdsAreRejectedWithoutReplacingOriginal() {
        OrderBook book = new OrderBook(INSTRUMENT);
        Order original = order(1, OrderSide.BUY, "500", 1);
        book.add(original);
        assertThrows(IllegalArgumentException.class, () -> book.add(original));
        assertThrows(IllegalArgumentException.class, () -> book.add(order(1, OrderSide.SELL, "510", 2)));
        assertSame(original, book.orderById(1));
        assertSame(original, book.bestBid());
        assertNull(book.bestAsk());
    }

    @Test
    void structurallyRemoveAnOrderAfterItBecomesFilled() {
        OrderBook book = new OrderBook(INSTRUMENT);
        Order order = order(1, OrderSide.BUY, "500", 1);
        book.add(order);
        order.applyFill(BigDecimal.ONE);
        assertSame(order, book.remove(1));
        assertNull(book.orderById(1));
        assertNull(book.bestBid());
        assertEquals(OrderStatus.FILLED, order.status());
    }
}
