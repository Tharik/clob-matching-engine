package com.trading.clob.engine;

import com.trading.clob.domain.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class TradingEngineCancellationTest {
    private static final Asset BTC = new Asset("BTC");
    private static final Asset BRL = new Asset("BRL");
    private static final Instrument INSTRUMENT = new Instrument(BTC, BRL);
    private final TradingEngine engine = new TradingEngine();
    private final Account owner = new Account("owner");
    private final Account other = new Account("other");

    TradingEngineCancellationTest() {
        engine.registerInstrument(INSTRUMENT);
        engine.registerAccount(owner);
        engine.registerAccount(other);
        owner.creditAvailable(BTC, d("2"));
        owner.creditAvailable(BRL, d("1000"));
        other.creditAvailable(BTC, d("2"));
        other.creditAvailable(BRL, d("1000"));
    }

    private static BigDecimal d(String value) { return new BigDecimal(value); }

    private PlacementResult place(String account, OrderSide side, String price, String quantity) {
        return engine.placeOrder(account, INSTRUMENT, side, d(price), d(quantity));
    }

    private OrderBook book() { return engine.bookFor(INSTRUMENT); }

    private static void balance(Account account, Asset asset, String available, String reserved) {
        assertEquals(0, d(available).compareTo(account.balanceOf(asset).available()));
        assertEquals(0, d(reserved).compareTo(account.balanceOf(asset).reserved()));
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void cancelOpenReleasesFullReservationAndRetainsOriginalOrder(OrderSide side) {
        PlacementResult placed = place("owner", side, "500.00", "1.00");
        Order order = engine.orderById(placed.orderId());
        BigDecimal remaining = order.remainingQuantity();
        long sequence = order.sequence();
        engine.cancelOrder("owner", order.id());
        assertSame(order, engine.orderById(order.id()));
        assertNull(book().orderById(order.id()));
        assertNull(book().bestBid());
        assertNull(book().bestAsk());
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(remaining, order.remainingQuantity());
        assertEquals(d("1.00"), order.originalQuantity());
        assertEquals(sequence, order.sequence());
        balance(owner, BTC, "2", "0");
        balance(owner, BRL, "1000", "0");
    }

    @Test
    void cancelPartialBuyReleasesOnlyRemainingLimitReservation() {
        place("other", OrderSide.SELL, "490", "0.4");
        PlacementResult placed = place("owner", OrderSide.BUY, "500", "1");
        Order order = engine.orderById(placed.orderId());
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.status());
        balance(owner, BRL, "504", "300");
        var otherBefore = other.balances();
        engine.cancelOrder("owner", order.id());
        balance(owner, BRL, "804", "0");
        balance(owner, BTC, "2.4", "0");
        assertEquals(otherBefore, other.balances());
        assertEquals(1, placed.trades().size());
        assertEquals(0, d("196").compareTo(placed.trades().getFirst().executionPrice().multiply(placed.trades().getFirst().executedQuantity())));
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(0, d("0.6").compareTo(order.remainingQuantity()));
        assertEquals(d("1"), order.originalQuantity());
        assertEquals(2, order.sequence());
        assertSame(order, engine.orderById(order.id()));
        assertNull(book().orderById(order.id()));
    }

    @Test
    void cancelPartialSellPreservesExecutedSettlementAndReleasesRemainingBase() {
        place("other", OrderSide.BUY, "500", "0.4");
        PlacementResult placed = place("owner", OrderSide.SELL, "490", "1");
        Order order = engine.orderById(placed.orderId());
        balance(owner, BTC, "1", "0.6");
        var otherBefore = other.balances();
        engine.cancelOrder("owner", order.id());
        balance(owner, BTC, "1.6", "0");
        balance(owner, BRL, "1200", "0");
        assertEquals(otherBefore, other.balances());
        assertEquals(1, placed.trades().size());
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(0, d("0.6").compareTo(order.remainingQuantity()));
        assertEquals(d("1"), order.originalQuantity());
        assertEquals(2, order.sequence());
        assertSame(order, engine.orderById(order.id()));
        assertNull(book().orderById(order.id()));
    }

    private void unchangedFailure(Class<? extends RuntimeException> type, String message,
                                  String accountId, long id, Order observed) {
        var ownerBefore = owner.balances();
        var otherBefore = other.balances();
        Order bid = book().bestBid();
        Order ask = book().bestAsk();
        OrderStatus status = observed.status();
        BigDecimal remaining = observed.remainingQuantity();
        Order membership = book().orderById(observed.id());
        RuntimeException error = assertThrows(type, () -> engine.cancelOrder(accountId, id));
        assertTrue(error.getMessage().contains(message));
        assertEquals(ownerBefore, owner.balances());
        assertEquals(otherBefore, other.balances());
        assertSame(bid, book().bestBid());
        assertSame(ask, book().bestAsk());
        assertSame(observed, engine.orderById(observed.id()));
        assertSame(membership, book().orderById(observed.id()));
        assertEquals(status, observed.status());
        assertEquals(remaining, observed.remainingQuantity());
    }

    @Test
    void unknownOrderIsRejectedWithoutMutation() {
        Order order = engine.orderById(place("owner", OrderSide.BUY, "500", "1").orderId());
        unchangedFailure(IllegalArgumentException.class, "Unknown order", "owner", 999, order);
        unchangedFailure(IllegalArgumentException.class, "Unknown order", "owner", 0, order);
    }

    @Test
    void filledOrderIsRejectedWithoutMutation() {
        PlacementResult sell = place("owner", OrderSide.SELL, "500", "1");
        place("other", OrderSide.BUY, "500", "1");
        Order filled = engine.orderById(sell.orderId());
        unchangedFailure(IllegalArgumentException.class, "FILLED", "owner", filled.id(), filled);
    }

    @Test
    void alreadyCancelledOrderIsRejectedWithoutMutation() {
        Order order = engine.orderById(place("owner", OrderSide.BUY, "500", "1").orderId());
        engine.cancelOrder("owner", order.id());
        unchangedFailure(IllegalArgumentException.class, "already CANCELLED", "owner", order.id(), order);
    }

    @Test
    void selfTradeCancelledOrderCannotBeCancelledAgain() {
        place("owner", OrderSide.SELL, "500", "1");
        Order cancelled = engine.orderById(place("owner", OrderSide.BUY, "500", "1").orderId());
        unchangedFailure(IllegalArgumentException.class, "already CANCELLED", "owner", cancelled.id(), cancelled);
    }

    @Test
    void anotherAccountCannotCancelOrder() {
        Order order = engine.orderById(place("owner", OrderSide.BUY, "500", "1").orderId());
        unchangedFailure(IllegalArgumentException.class, "another account", "other", order.id(), order);
    }

    @Test
    void nullBlankAndUnknownAccountsAreRejectedWithoutMutation() {
        Order order = engine.orderById(place("owner", OrderSide.SELL, "500", "1").orderId());
        unchangedFailure(NullPointerException.class, "accountId", null, order.id(), order);
        unchangedFailure(IllegalArgumentException.class, "blank", " \t", order.id(), order);
        unchangedFailure(IllegalArgumentException.class, "Unknown account", "absent", order.id(), order);
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void cancellingBestExposesNextTimePriorityThenNextPrice(OrderSide side) {
        PlacementResult first = place("owner", side, "500.0", "0.4");
        PlacementResult second = place("owner", side, "500.00", "0.4");
        PlacementResult worse = place("owner", side, side == OrderSide.BUY ? "490" : "510", "0.4");
        engine.cancelOrder("owner", first.orderId());
        assertEquals(second.orderId(), (side == OrderSide.BUY ? book().bestBid() : book().bestAsk()).id());
        engine.cancelOrder("owner", second.orderId());
        assertEquals(worse.orderId(), (side == OrderSide.BUY ? book().bestBid() : book().bestAsk()).id());
        assertEquals(OrderStatus.OPEN, engine.orderById(worse.orderId()).status());
        assertNotNull(engine.orderById(first.orderId()));
        assertNotNull(engine.orderById(second.orderId()));
    }

    @Test
    void missingActiveBookOrderIsAnInvariantFailureBeforeMutation() {
        Order order = engine.orderById(place("owner", OrderSide.BUY, "500", "1").orderId());
        book().remove(order.id());
        unchangedFailure(IllegalStateException.class, "missing", "owner", order.id(), order);
    }

    @Test
    void insufficientReservationIsAnInvariantFailureBeforeStructuralRemoval() {
        Order order = engine.orderById(place("owner", OrderSide.BUY, "500", "1").orderId());
        owner.consumeReserved(BRL, d("1"));
        unchangedFailure(IllegalStateException.class, "reservation", "owner", order.id(), order);
    }

    @Test
    void cancellingOneOrderReleasesOnlyItsReservation() {
        PlacementResult first = place("owner", OrderSide.BUY, "500", "1");
        PlacementResult second = place("owner", OrderSide.BUY, "400", "1");
        engine.cancelOrder("owner", first.orderId());
        balance(owner, BRL, "600", "400");
        assertEquals(second.orderId(), book().bestBid().id());
        assertEquals(OrderStatus.OPEN, engine.orderById(second.orderId()).status());
    }
}
