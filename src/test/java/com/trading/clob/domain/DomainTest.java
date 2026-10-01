package com.trading.clob.domain;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DomainTest {
    private static final Asset BTC = new Asset("BTC");
    private static final Asset BRL = new Asset("BRL");
    private static final Instrument INSTRUMENT = new Instrument(BTC, BRL);

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }

    private static Order order(BigDecimal price, BigDecimal quantity) {
        return new Order(1, "account", INSTRUMENT, OrderSide.BUY, price, quantity, 1);
    }

    @Test
    void assetsAndInstrumentsAreValues() {
        assertEquals("BTC", BTC.code());
        assertEquals(BTC, new Asset("BTC"));
        assertEquals(BTC, INSTRUMENT.baseAsset());
        assertEquals(BRL, INSTRUMENT.quoteAsset());
        assertEquals(INSTRUMENT, new Instrument(new Asset("BTC"), new Asset("BRL")));
        assertThrows(IllegalArgumentException.class, () -> new Instrument(BTC, new Asset("BTC")));
        assertThrows(NullPointerException.class, () -> new Asset(null));
        assertThrows(NullPointerException.class, () -> new Instrument(null, BRL));
        assertThrows(NullPointerException.class, () -> new Instrument(BTC, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void blankIdentifiersAreRejected(String value) {
        assertThrows(IllegalArgumentException.class, () -> new Asset(value));
        assertThrows(IllegalArgumentException.class, () -> new Account(value));
        assertThrows(IllegalArgumentException.class, () -> new Order(1, value, INSTRUMENT,
                OrderSide.BUY, BigDecimal.ONE, BigDecimal.ONE, 1));
    }

    @Test
    void balancesRejectNegativeAndNullAmounts() {
        assertThrows(IllegalArgumentException.class, () -> new Balance(decimal("-1"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Balance(BigDecimal.ZERO, decimal("-1")));
        assertThrows(NullPointerException.class, () -> new Balance(null, BigDecimal.ZERO));
        assertThrows(NullPointerException.class, () -> new Balance(BigDecimal.ZERO, null));
        assertEquals(0, new Balance(decimal("0.00"), decimal("0.0")).available().compareTo(BigDecimal.ZERO));
    }

    @Test
    void accountProtectsBalanceSnapshotsAndValidatesUpdates() {
        Account account = new Account("account");
        Balance balance = new Balance(decimal("100"), BigDecimal.ZERO);
        account.setBalance(BRL, balance);
        var snapshot = account.balances();
        assertEquals("account", account.id());
        assertEquals(balance, snapshot.get(BRL));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        account.setBalance(BRL, new Balance(decimal("50"), decimal("50")));
        assertEquals(balance, snapshot.get(BRL));
        assertThrows(NullPointerException.class, () -> account.setBalance(null, balance));
        assertThrows(NullPointerException.class, () -> account.setBalance(BTC, null));
        assertEquals(1, account.balances().size());
        assertThrows(NullPointerException.class, () -> new Account(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void orderAndTradeRequirePositivePriceAndQuantity(String value) {
        BigDecimal invalid = decimal(value);
        assertThrows(IllegalArgumentException.class,
                () -> order(invalid, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> order(BigDecimal.ONE, invalid));
        assertThrows(IllegalArgumentException.class,
                () -> new Trade(1, 2, INSTRUMENT, invalid, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new Trade(1, 2, INSTRUMENT, BigDecimal.ONE, invalid));
    }

    @Test
    void orderRetainsAcceptedValuesAndOriginalPriority() {
        Order order = new Order(12, "account", INSTRUMENT, OrderSide.SELL,
                decimal("500"), decimal("1"), 42);
        assertEquals(12, order.id());
        assertEquals("account", order.accountId());
        assertEquals(INSTRUMENT, order.instrument());
        assertEquals(OrderSide.SELL, order.side());
        assertEquals(decimal("500"), order.limitPrice());
        assertEquals(decimal("1"), order.originalQuantity());
        assertEquals(decimal("1"), order.remainingQuantity());
        assertEquals(42, order.sequence());
        assertEquals(OrderStatus.OPEN, order.status());
        Order partial = order(BigDecimal.ONE, decimal("1"));
        partial.applyFill(decimal("0.6"));
        assertEquals(BigDecimal.ONE, partial.originalQuantity());
        assertEquals(1, partial.sequence());
    }

    @Test
    void orderRejectsInvalidIdentifiersQuantitiesAndStates() {
        for (long invalid : new long[] {0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new Order(invalid, "account",
                    INSTRUMENT, OrderSide.BUY, BigDecimal.ONE, BigDecimal.ONE, 1));
            assertThrows(IllegalArgumentException.class, () -> new Order(1, "account",
                    INSTRUMENT, OrderSide.BUY, BigDecimal.ONE, BigDecimal.ONE, invalid));
        }
    }

    @Test
    void orderRejectsAllMissingObjects() {
        assertThrows(NullPointerException.class, () -> new Order(1, null, INSTRUMENT, OrderSide.BUY, BigDecimal.ONE, BigDecimal.ONE, 1));
        assertThrows(NullPointerException.class, () -> new Order(1, "account", null, OrderSide.BUY, BigDecimal.ONE, BigDecimal.ONE, 1));
        assertThrows(NullPointerException.class, () -> new Order(1, "account", INSTRUMENT, null, BigDecimal.ONE, BigDecimal.ONE, 1));
        assertThrows(NullPointerException.class, () -> order(null, BigDecimal.ONE));
        assertThrows(NullPointerException.class, () -> order(BigDecimal.ONE, null));
    }

    @Test
    void tradeIsSelfContainedAndValidatesIdentifiersAndObjects() {
        Trade trade = new Trade(1, 2, INSTRUMENT, decimal("490"), decimal("0.4"));
        assertEquals(1, trade.buyOrderId());
        assertEquals(2, trade.sellOrderId());
        assertEquals(INSTRUMENT, trade.instrument());
        assertEquals(0, decimal("490").compareTo(trade.executionPrice()));
        assertEquals(0, decimal("0.4").compareTo(trade.executedQuantity()));
        assertThrows(IllegalArgumentException.class, () -> new Trade(0, 2, INSTRUMENT, BigDecimal.ONE, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new Trade(1, -1, INSTRUMENT, BigDecimal.ONE, BigDecimal.ONE));
        assertThrows(NullPointerException.class, () -> new Trade(1, 2, null, BigDecimal.ONE, BigDecimal.ONE));
        assertThrows(NullPointerException.class, () -> new Trade(1, 2, INSTRUMENT, null, BigDecimal.ONE));
        assertThrows(NullPointerException.class, () -> new Trade(1, 2, INSTRUMENT, BigDecimal.ONE, null));
    }

    @Test
    void numericEqualityIgnoresScaleWithoutRounding() {
        Balance first = new Balance(decimal("1.0"), decimal("2.00"));
        Balance second = new Balance(decimal("1.00"), decimal("2.0"));
        assertEquals(0, first.available().compareTo(second.available()));
        assertEquals(0, first.reserved().compareTo(second.reserved()));
        assertEquals(decimal("1.0"), first.available());
        assertEquals(decimal("2.00"), first.reserved());
        assertEquals(decimal("1.00"), second.available());
        assertEquals(decimal("2.0"), second.reserved());
        Trade firstTrade = new Trade(1, 2, INSTRUMENT, decimal("490.0"), decimal("0.40"));
        Trade secondTrade = new Trade(1, 2, INSTRUMENT, decimal("490.00"), decimal("0.400"));
        assertEquals(0, firstTrade.executionPrice().compareTo(secondTrade.executionPrice()));
        assertEquals(0, firstTrade.executedQuantity().compareTo(secondTrade.executedQuantity()));
        assertEquals(decimal("490.0"), firstTrade.executionPrice());
        assertEquals(decimal("0.40"), firstTrade.executedQuantity());
        assertEquals(decimal("490.00"), secondTrade.executionPrice());
        assertEquals(decimal("0.400"), secondTrade.executedQuantity());
        BigDecimal precise = decimal("0.12345678901234567890123456789");
        assertEquals(precise, new Balance(precise, BigDecimal.ZERO).available());
        assertEquals(precise, new Trade(1, 2, INSTRUMENT, precise, precise).executedQuantity());
    }
    @Test
    void partialAndFullFillsPreserveOriginalValuesAndPriority() {
        Order order = new Order(12, "account", INSTRUMENT, OrderSide.SELL,
                decimal("500.00"), decimal("1.00"), 42);
        order.applyFill(decimal("0.4"));
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.status());
        assertEquals(0, decimal("0.6").compareTo(order.remainingQuantity()));
        assertEquals(decimal("1.00"), order.originalQuantity());
        assertEquals(42, order.sequence());
        order.applyFill(decimal("0.600"));
        assertEquals(OrderStatus.FILLED, order.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(order.remainingQuantity()));
        assertEquals(decimal("1.00"), order.originalQuantity());
        assertEquals(42, order.sequence());
        assertEquals(12, order.id());
        assertEquals("account", order.accountId());
        assertEquals(INSTRUMENT, order.instrument());
        assertEquals(OrderSide.SELL, order.side());
        assertEquals(decimal("500.00"), order.limitPrice());
    }

    @Test
    void fullFillCanHappenDirectlyFromOpen() {
        Order order = order(BigDecimal.ONE, decimal("1.00"));
        order.applyFill(decimal("1.0"));
        assertEquals(OrderStatus.FILLED, order.status());
        assertEquals(0, order.remainingQuantity().signum());
    }

    @Test
    void invalidFillsDoNotMutateOrder() {
        Order order = order(BigDecimal.ONE, BigDecimal.ONE);
        assertThrows(NullPointerException.class, () -> order.applyFill(null));
        for (String invalid : new String[] {"0", "-1", "1.01"}) {
            assertThrows(IllegalArgumentException.class, () -> order.applyFill(decimal(invalid)));
            assertEquals(BigDecimal.ONE, order.remainingQuantity());
            assertEquals(OrderStatus.OPEN, order.status());
        }
        order.applyFill(decimal("0.75"));
        assertThrows(IllegalArgumentException.class, () -> order.applyFill(decimal("0.5")));
        assertEquals(decimal("0.25"), order.remainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.status());
    }

    @Test
    void cancelOpenOrderPreservesQuantityAndPriority() {
        Order order = order(BigDecimal.ONE, decimal("1.00"));
        order.cancel();
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(decimal("1.00"), order.remainingQuantity());
        assertEquals(decimal("1.00"), order.originalQuantity());
        assertEquals(1, order.sequence());
    }

    @Test
    void cancelPartiallyFilledOrderPreservesQuantityAndPriority() {
        Order order = order(BigDecimal.ONE, decimal("1.00"));
        order.applyFill(decimal("0.4"));
        BigDecimal remaining = order.remainingQuantity();
        order.cancel();
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(remaining, order.remainingQuantity());
        assertEquals(decimal("1.00"), order.originalQuantity());
        assertEquals(1, order.sequence());
    }

    @Test
    void terminalOrdersRejectCancellationAndFurtherFills() {
        Order filled = order(BigDecimal.ONE, BigDecimal.ONE);
        filled.applyFill(BigDecimal.ONE);
        assertThrows(IllegalStateException.class, filled::cancel);
        assertThrows(IllegalArgumentException.class, () -> filled.applyFill(BigDecimal.ONE));
        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(0, filled.remainingQuantity().signum());
        Order cancelled = order(BigDecimal.ONE, BigDecimal.ONE);
        cancelled.cancel();
        assertThrows(IllegalStateException.class, cancelled::cancel);
        assertThrows(IllegalStateException.class, () -> cancelled.applyFill(decimal("0.5")));
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(BigDecimal.ONE, cancelled.remainingQuantity());
    }

    @Test
    void directBalanceLookupReturnsCurrentValueOrZeroWithoutAddingEntries() {
        Account account = new Account("account");
        Balance missing = account.balanceOf(BTC);
        assertEquals(0, missing.available().compareTo(BigDecimal.ZERO));
        assertEquals(0, missing.reserved().compareTo(BigDecimal.ZERO));
        assertTrue(account.balances().isEmpty());
        Balance initial = new Balance(decimal("1.00"), BigDecimal.ZERO);
        account.setBalance(BTC, initial);
        assertSame(initial, account.balanceOf(new Asset("BTC")));
        Balance updated = new Balance(decimal("0.50"), decimal("0.50"));
        account.setBalance(BTC, updated);
        assertSame(updated, account.balanceOf(BTC));
        assertThrows(NullPointerException.class, () -> account.balanceOf(null));
    }

}
