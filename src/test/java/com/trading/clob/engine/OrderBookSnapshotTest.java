package com.trading.clob.engine;

import com.trading.clob.domain.Account;
import com.trading.clob.domain.Asset;
import com.trading.clob.domain.Instrument;
import com.trading.clob.domain.OrderSide;
import com.trading.clob.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookSnapshotTest {
    private static final Asset BTC = new Asset("BTC");
    private static final Asset BRL = new Asset("BRL");
    private static final Instrument INSTRUMENT = new Instrument(BTC, BRL);
    private final TradingEngine engine = new TradingEngine();
    private final Account buyer = new Account("buyer");
    private final Account seller = new Account("seller");

    OrderBookSnapshotTest() {
        buyer.creditAvailable(BRL, decimal("10000"));
        seller.creditAvailable(BTC, decimal("10"));
        engine.registerAccount(buyer);
        engine.registerAccount(seller);
        engine.registerInstrument(INSTRUMENT);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }

    private PlacementResult place(OrderSide side, String price, String quantity) {
        return engine.placeOrder(side == OrderSide.BUY ? buyer.id() : seller.id(), INSTRUMENT,
                side, decimal(price), decimal(quantity));
    }

    private static List<BookOrderView> side(OrderBookSnapshot snapshot, OrderSide side) {
        return side == OrderSide.BUY ? snapshot.bids() : snapshot.asks();
    }

    private static void numeric(String expected, BigDecimal actual) {
        assertEquals(0, decimal(expected).compareTo(actual));
    }

    @Test
    void registeredEmptyInstrumentHasEmptySides() {
        OrderBookSnapshot snapshot = engine.orderBookSnapshot(INSTRUMENT);
        assertEquals(INSTRUMENT, snapshot.instrument());
        assertTrue(snapshot.bids().isEmpty());
        assertTrue(snapshot.asks().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.bids().add(new BookOrderView(1, decimal("500"), BigDecimal.ONE, OrderStatus.OPEN)));
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void sidesPreservePricePriorityAndFifoAtNumericEquivalentPrices(OrderSide orderSide) {
        String worsePrice = orderSide == OrderSide.BUY ? "490" : "510";
        PlacementResult worse = place(orderSide, worsePrice, "1");
        PlacementResult first = place(orderSide, "500.0", "1");
        PlacementResult second = place(orderSide, "500.00", "2");
        OrderBookSnapshot snapshot = engine.orderBookSnapshot(INSTRUMENT);
        List<BookOrderView> views = side(snapshot, orderSide);
        assertEquals(List.of(first.orderId(), second.orderId(), worse.orderId()),
                List.of(views.get(0).orderId(), views.get(1).orderId(), views.get(2).orderId()));
        assertEquals(decimal("500.0"), views.get(0).limitPrice());
        assertEquals(decimal("500.00"), views.get(1).limitPrice());
        numeric(worsePrice, views.get(2).limitPrice());
        numeric("2", views.get(1).remainingQuantity());
        assertEquals(OrderStatus.OPEN, views.get(1).status());
        assertTrue(side(snapshot, orderSide == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY).isEmpty());
        // Execution confirms that snapshot head represents the real best order.
        PlacementResult execution = place(orderSide == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY, "500", "0.1");
        assertEquals(views.getFirst().orderId(), orderSide == OrderSide.BUY
                ? execution.trades().getFirst().buyOrderId() : execution.trades().getFirst().sellOrderId());
    }

    @Test
    void snapshotContainsBothSidesWithoutMutatingBalancesOrPriority() {
        PlacementResult bid = place(OrderSide.BUY, "490", "1");
        PlacementResult ask = place(OrderSide.SELL, "510", "1");
        var buyerBefore = buyer.balances();
        var sellerBefore = seller.balances();
        OrderBookSnapshot snapshot = engine.orderBookSnapshot(INSTRUMENT);
        assertEquals(1, snapshot.bids().size());
        assertEquals(1, snapshot.asks().size());
        assertEquals(bid.orderId(), snapshot.bids().getFirst().orderId());
        assertEquals(ask.orderId(), snapshot.asks().getFirst().orderId());
        assertEquals(buyerBefore, buyer.balances());
        assertEquals(sellerBefore, seller.balances());
        assertEquals(snapshot, engine.orderBookSnapshot(INSTRUMENT));
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void partialRestingFillUpdatesViewWithoutLosingPriority(OrderSide orderSide) {
        PlacementResult first = place(orderSide, "500", "1");
        PlacementResult second = place(orderSide, "500", "1");
        OrderBookSnapshot before = engine.orderBookSnapshot(INSTRUMENT);
        place(orderSide == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY, "500", "0.4");
        List<BookOrderView> views = side(engine.orderBookSnapshot(INSTRUMENT), orderSide);
        assertEquals(2, views.size());
        assertEquals(first.orderId(), views.getFirst().orderId());
        assertEquals(OrderStatus.PARTIALLY_FILLED, views.getFirst().status());
        numeric("0.6", views.getFirst().remainingQuantity());
        assertEquals(second.orderId(), views.get(1).orderId());
        numeric("1", views.get(1).remainingQuantity());
        assertEquals(OrderStatus.OPEN, side(before, orderSide).getFirst().status());
        numeric("1", side(before, orderSide).getFirst().remainingQuantity());
    }

    @Test
    void filledAndCancelledOrdersAreAbsentFromNewSnapshot() {
        PlacementResult ask = place(OrderSide.SELL, "500", "1");
        PlacementResult bid = place(OrderSide.BUY, "490", "1");
        OrderBookSnapshot before = engine.orderBookSnapshot(INSTRUMENT);
        PlacementResult filledBuy = place(OrderSide.BUY, "500", "1");
        assertEquals(OrderStatus.FILLED, filledBuy.status());
        OrderBookSnapshot afterFill = engine.orderBookSnapshot(INSTRUMENT);
        assertTrue(afterFill.asks().isEmpty());
        assertEquals(1, afterFill.bids().size());
        assertEquals(bid.orderId(), afterFill.bids().getFirst().orderId());
        engine.cancelOrder(buyer.id(), bid.orderId());
        OrderBookSnapshot afterCancel = engine.orderBookSnapshot(INSTRUMENT);
        assertTrue(afterCancel.bids().isEmpty());
        assertTrue(afterCancel.asks().isEmpty());
        assertEquals(ask.orderId(), before.asks().getFirst().orderId());
        assertEquals(bid.orderId(), before.bids().getFirst().orderId());
        assertEquals(OrderStatus.OPEN, before.asks().getFirst().status());
        assertEquals(OrderStatus.OPEN, before.bids().getFirst().status());
        assertEquals(1, afterFill.bids().size());
    }

    @Test
    void snapshotsAreIsolatedByInstrument() {
        Asset eth = new Asset("ETH");
        Instrument ethBrl = new Instrument(eth, BRL);
        engine.registerInstrument(ethBrl);
        seller.creditAvailable(eth, decimal("1"));
        PlacementResult btcBid = place(OrderSide.BUY, "500", "1");
        PlacementResult ethAsk = engine.placeOrder(seller.id(), ethBrl, OrderSide.SELL, decimal("490"), BigDecimal.ONE);
        OrderBookSnapshot btcSnapshot = engine.orderBookSnapshot(INSTRUMENT);
        OrderBookSnapshot ethSnapshot = engine.orderBookSnapshot(ethBrl);
        assertEquals(INSTRUMENT, btcSnapshot.instrument());
        assertEquals(ethBrl, ethSnapshot.instrument());
        assertEquals(btcBid.orderId(), btcSnapshot.bids().getFirst().orderId());
        assertTrue(btcSnapshot.asks().isEmpty());
        assertEquals(ethAsk.orderId(), ethSnapshot.asks().getFirst().orderId());
        assertTrue(ethSnapshot.bids().isEmpty());
        engine.cancelOrder(buyer.id(), btcBid.orderId());
        assertEquals(ethSnapshot, engine.orderBookSnapshot(ethBrl));
    }

    @Test
    void listsCannotBeModifiedAndOldSnapshotsDoNotFollowInsertion() {
        place(OrderSide.BUY, "490", "1");
        place(OrderSide.SELL, "510", "1");
        OrderBookSnapshot before = engine.orderBookSnapshot(INSTRUMENT);
        assertThrows(UnsupportedOperationException.class, before.bids()::clear);
        assertThrows(UnsupportedOperationException.class, before.asks()::clear);
        assertThrows(UnsupportedOperationException.class, () -> before.bids().set(0, before.bids().getFirst()));
        assertThrows(UnsupportedOperationException.class, () -> before.asks().add(before.asks().getFirst()));
        place(OrderSide.BUY, "480", "1");
        place(OrderSide.SELL, "520", "1");
        assertEquals(1, before.bids().size());
        assertEquals(1, before.asks().size());
        assertEquals(2, engine.orderBookSnapshot(INSTRUMENT).bids().size());
        assertEquals(2, engine.orderBookSnapshot(INSTRUMENT).asks().size());
    }

    @Test
    void snapshotConstructorDefensivelyCopiesCallerLists() {
        BookOrderView view = new BookOrderView(1, decimal("500.00"), decimal("1.00"), OrderStatus.OPEN);
        List<BookOrderView> bids = new ArrayList<>(List.of(view));
        List<BookOrderView> asks = new ArrayList<>(List.of(view));
        OrderBookSnapshot snapshot = new OrderBookSnapshot(INSTRUMENT, bids, asks);
        bids.clear();
        asks.clear();
        assertEquals(List.of(view), snapshot.bids());
        assertEquals(List.of(view), snapshot.asks());
        assertEquals(decimal("500.00"), snapshot.bids().getFirst().limitPrice());
        assertEquals(decimal("1.00"), snapshot.bids().getFirst().remainingQuantity());
    }

    @Test
    void invalidInstrumentQueriesAreRejectedWithoutSideEffectsOrIdConsumption() {
        PlacementResult bid = place(OrderSide.BUY, "490", "1");
        var buyerBefore = buyer.balances();
        var sellerBefore = seller.balances();
        OrderBookSnapshot before = engine.orderBookSnapshot(INSTRUMENT);
        assertThrows(NullPointerException.class, () -> engine.orderBookSnapshot(null));
        assertThrows(IllegalArgumentException.class,
                () -> engine.orderBookSnapshot(new Instrument(new Asset("ETH"), BRL)));
        assertEquals(before, engine.orderBookSnapshot(INSTRUMENT));
        assertEquals(buyerBefore, buyer.balances());
        assertEquals(sellerBefore, seller.balances());
        PlacementResult next = place(OrderSide.SELL, "510", "1");
        assertEquals(bid.orderId() + 1, next.orderId());
    }
}
