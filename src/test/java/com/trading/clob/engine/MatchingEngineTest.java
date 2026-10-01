package com.trading.clob.engine;

import com.trading.clob.domain.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineTest {
    private static final Instrument INSTRUMENT = new Instrument(new Asset("BTC"), new Asset("BRL"));
    private final OrderBook book = new OrderBook(INSTRUMENT);
    private final MatchingEngine engine = new MatchingEngine();

    private static Order order(long id, OrderSide side, String price, String quantity) {
        return new Order(id, "account-" + id, INSTRUMENT, side,
                new BigDecimal(price), new BigDecimal(quantity), id);
    }

    private List<Trade> matchAll(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        while (incoming.remainingQuantity().signum() > 0) {
            Trade trade = engine.matchNext(book, incoming);
            if (trade == null) break;
            trades.add(trade);
        }
        return trades;
    }

    private static void numeric(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void buyMatchesBestAskAtRestingPriceWithCorrectIds() {
        Order ask = order(1, OrderSide.SELL, "490.00", "1");
        book.add(order(2, OrderSide.SELL, "495", "1"));
        book.add(ask);
        Order buy = order(3, OrderSide.BUY, "500", "1");
        var trades = matchAll(buy);
        assertEquals(1, trades.size());
        Trade trade = trades.getFirst();
        assertEquals(3, trade.buyOrderId());
        assertEquals(1, trade.sellOrderId());
        assertEquals(INSTRUMENT, trade.instrument());
        assertEquals(new BigDecimal("490.00"), trade.executionPrice());
        numeric("1", trade.executedQuantity());
        assertEquals(OrderStatus.FILLED, buy.status());
        assertEquals(OrderStatus.FILLED, ask.status());
        assertNull(book.orderById(1));
        assertEquals(2, book.bestAsk().id());
        assertNull(book.orderById(3));
    }

    @Test
    void sellMatchesBestBidAtRestingPriceWithCorrectIds() {
        book.add(order(1, OrderSide.BUY, "510", "1"));
        book.add(order(2, OrderSide.BUY, "500", "1"));
        Order sell = order(3, OrderSide.SELL, "490", "1");
        Trade trade = matchAll(sell).getFirst();
        assertEquals(1, trade.buyOrderId());
        assertEquals(3, trade.sellOrderId());
        numeric("510", trade.executionPrice());
        assertEquals(OrderStatus.FILLED, sell.status());
        assertNull(book.orderById(1));
        assertEquals(2, book.bestBid().id());
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void nonCrossingPricesLeaveBothOrdersUnchanged(OrderSide side) {
        Order resting = order(1, side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY,
                side == OrderSide.BUY ? "510" : "490", "1");
        book.add(resting);
        Order incoming = order(2, side, "500", "1");
        assertTrue(matchAll(incoming).isEmpty());
        assertEquals(OrderStatus.OPEN, incoming.status());
        assertEquals(OrderStatus.OPEN, resting.status());
        numeric("1", incoming.remainingQuantity());
        numeric("1", resting.remainingQuantity());
        assertSame(resting, book.orderById(1));
        assertNull(book.orderById(2));
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void emptyOppositeSideDoesNotMatchSameSide(OrderSide side) {
        Order sameSide = order(1, side, "500", "1");
        book.add(sameSide);
        Order incoming = order(2, side, "500", "1");
        assertTrue(matchAll(incoming).isEmpty());
        assertEquals(OrderStatus.OPEN, incoming.status());
        assertSame(sameSide, book.orderById(1));
    }

    @Test
    void incomingPartiallyFillsWithoutRestingItsRemainder() {
        book.add(order(1, OrderSide.SELL, "490", "0.4"));
        Order incoming = order(2, OrderSide.BUY, "500", "1");
        assertEquals(1, matchAll(incoming).size());
        assertEquals(OrderStatus.PARTIALLY_FILLED, incoming.status());
        numeric("0.6", incoming.remainingQuantity());
        numeric("1", incoming.originalQuantity());
        assertEquals(2, incoming.sequence());
        assertNull(book.bestAsk());
        assertNull(book.bestBid());
        assertNull(book.orderById(2));
    }

    @Test
    void partiallyFilledRestingOrderKeepsHeadPositionAndSequence() {
        Order first = order(1, OrderSide.SELL, "500", "1");
        Order second = order(2, OrderSide.SELL, "500", "1");
        book.add(first);
        book.add(second);
        Order incoming = order(3, OrderSide.BUY, "500", "0.4");
        matchAll(incoming);
        assertSame(first, book.bestAsk());
        assertSame(first, book.orderById(1));
        numeric("0.6", first.remainingQuantity());
        numeric("1", first.originalQuantity());
        assertEquals(1, first.sequence());
        assertEquals(OrderStatus.PARTIALLY_FILLED, first.status());
        assertEquals(OrderStatus.FILLED, incoming.status());
        assertEquals(1, matchAll(order(4, OrderSide.BUY, "500", "0.2")).getFirst().sellOrderId());
        numeric("1", second.remainingQuantity());
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void multipleExecutionsRespectPriceThenFifoAndStopAtNonCrossingPrice(OrderSide side) {
        OrderSide opposite = side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        String bestPrice = side == OrderSide.BUY ? "490" : "510";
        String outsidePrice = side == OrderSide.BUY ? "501" : "499";
        Order worse = order(1, opposite, "500", "0.4");
        Order first = order(2, opposite, bestPrice, "0.3");
        Order second = order(3, opposite, bestPrice, "0.2");
        Order outside = order(4, opposite, outsidePrice, "1");
        book.add(worse);
        book.add(first);
        book.add(second);
        book.add(outside);
        Order incoming = order(5, side, "500", "2");
        var trades = matchAll(incoming);
        assertEquals(3, trades.size());
        long[] expectedIds = {2, 3, 1};
        for (int i = 0; i < expectedIds.length; i++) {
            assertEquals(expectedIds[i], side == OrderSide.BUY ? trades.get(i).sellOrderId() : trades.get(i).buyOrderId());
            assertEquals(5, side == OrderSide.BUY ? trades.get(i).buyOrderId() : trades.get(i).sellOrderId());
            assertNull(book.orderById(expectedIds[i]));
        }
        numeric(bestPrice, trades.get(0).executionPrice());
        numeric(bestPrice, trades.get(1).executionPrice());
        numeric("500", trades.get(2).executionPrice());
        numeric("0.3", trades.get(0).executedQuantity());
        numeric("0.2", trades.get(1).executedQuantity());
        numeric("0.4", trades.get(2).executedQuantity());
        numeric("1.1", incoming.remainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, incoming.status());
        assertSame(outside, side == OrderSide.BUY ? book.bestAsk() : book.bestBid());
        assertEquals(OrderStatus.OPEN, outside.status());
        numeric("1", outside.remainingQuantity());
    }

    @Test
    void incomingStopsWhenFilledEvenWithMoreCrossingLiquidity() {
        book.add(order(1, OrderSide.SELL, "490", "0.3"));
        Order second = order(2, OrderSide.SELL, "495", "1");
        book.add(second);
        Order incoming = order(3, OrderSide.BUY, "500", "0.5");
        var trades = matchAll(incoming);
        assertEquals(2, trades.size());
        numeric("0.2", trades.get(1).executedQuantity());
        assertEquals(OrderStatus.FILLED, incoming.status());
        numeric("0", incoming.remainingQuantity());
        numeric("0.8", second.remainingQuantity());
        assertSame(second, book.bestAsk());
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void numericEquivalentPricesCrossWithoutChangingTheirRepresentation(OrderSide side) {
        Order resting = order(1, side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY, "500.00", "1.00");
        Order incoming = order(2, side, "500.0", "1.0");
        book.add(resting);
        Trade trade = matchAll(incoming).getFirst();
        assertEquals(new BigDecimal("500.00"), trade.executionPrice());
        assertEquals(new BigDecimal("500.00"), resting.limitPrice());
        assertEquals(new BigDecimal("500.0"), incoming.limitPrice());
        assertEquals(OrderStatus.FILLED, resting.status());
        assertEquals(OrderStatus.FILLED, incoming.status());
    }

    @Test
    void wrongInstrumentIsRejectedBeforeAnyMutation() {
        Order resting = order(1, OrderSide.SELL, "490", "1");
        book.add(resting);
        Order incoming = new Order(2, "other", new Instrument(new Asset("ETH"), new Asset("BRL")),
                OrderSide.BUY, new BigDecimal("500"), BigDecimal.ONE, 2);
        assertThrows(IllegalArgumentException.class, () -> engine.matchNext(book, incoming));
        assertSame(resting, book.bestAsk());
        assertEquals(OrderStatus.OPEN, resting.status());
        assertEquals(OrderStatus.OPEN, incoming.status());
    }

    @Test
    void tradeValuesDoNotFollowLaterOrderChanges() {
        Order resting = order(1, OrderSide.SELL, "500", "1");
        book.add(resting);
        Trade trade = engine.matchNext(book, order(2, OrderSide.BUY, "500", "0.4"));
        resting.applyFill(new BigDecimal("0.2"));
        numeric("0.4", trade.executedQuantity());
        numeric("500", trade.executionPrice());
        assertEquals(2, trade.buyOrderId());
        assertEquals(1, trade.sellOrderId());
        assertNull(engine.matchNext(book, order(3, OrderSide.BUY, "490", "1")));
    }

    @Test
    void matchNextExecutesOnlyOneCounterpartyAndCanResume() {
        book.add(order(1, OrderSide.SELL, "490", "0.4"));
        book.add(order(2, OrderSide.SELL, "495", "0.6"));
        Order incoming = order(3, OrderSide.BUY, "500", "1");
        assertEquals(1, engine.matchNext(book, incoming).sellOrderId());
        assertEquals(2, book.bestAsk().id());
        numeric("0.6", incoming.remainingQuantity());
        assertEquals(2, engine.matchNext(book, incoming).sellOrderId());
        assertEquals(OrderStatus.FILLED, incoming.status());
        assertNull(book.bestAsk());
        assertNull(engine.matchNext(book, order(4, OrderSide.BUY, "500", "1")));
    }

    @Test
    void invalidIncomingOrdersAndDuplicateIdsAreRejectedBeforeMutation() {
        Order resting = order(1, OrderSide.SELL, "500", "1");
        book.add(resting);
        assertThrows(IllegalArgumentException.class, () -> engine.matchNext(book, resting));
        assertThrows(IllegalArgumentException.class,
                () -> engine.matchNext(book, order(1, OrderSide.BUY, "500", "1")));
        Order filled = order(2, OrderSide.BUY, "500", "1");
        filled.applyFill(BigDecimal.ONE);
        Order cancelled = order(3, OrderSide.BUY, "500", "1");
        cancelled.cancel();
        assertThrows(IllegalArgumentException.class, () -> engine.matchNext(book, filled));
        assertThrows(IllegalArgumentException.class, () -> engine.matchNext(book, cancelled));
        assertThrows(NullPointerException.class, () -> engine.matchNext(null, resting));
        assertThrows(NullPointerException.class, () -> engine.matchNext(book, null));
        assertSame(resting, book.bestAsk());
        assertEquals(OrderStatus.OPEN, resting.status());
        numeric("1", resting.remainingQuantity());
    }

    @Test
    void inactiveRestingOrderIsDetectedBeforeIncomingMutation() {
        Order resting = order(1, OrderSide.SELL, "500", "1");
        book.add(resting);
        resting.cancel();
        Order incoming = order(2, OrderSide.BUY, "500", "1");
        assertThrows(IllegalStateException.class, () -> engine.matchNext(book, incoming));
        assertEquals(OrderStatus.OPEN, incoming.status());
        numeric("1", incoming.remainingQuantity());
        assertSame(resting, book.bestAsk());
    }
}
