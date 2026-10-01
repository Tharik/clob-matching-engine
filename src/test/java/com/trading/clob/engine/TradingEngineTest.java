package com.trading.clob.engine;

import com.trading.clob.domain.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class TradingEngineTest {
    private static final Asset BTC = new Asset("BTC");
    private static final Asset BRL = new Asset("BRL");
    private static final Instrument INSTRUMENT = new Instrument(BTC, BRL);
    private final TradingEngine engine = new TradingEngine();

    TradingEngineTest() {
        engine.registerInstrument(INSTRUMENT);
    }

    private static BigDecimal d(String value) { return new BigDecimal(value); }

    private Account account(String id, String base, String quote) {
        Account account = new Account(id);
        if (d(base).signum() > 0) account.creditAvailable(BTC, d(base));
        if (d(quote).signum() > 0) account.creditAvailable(BRL, d(quote));
        engine.registerAccount(account);
        return account;
    }

    private PlacementResult place(String account, OrderSide side, String price, String quantity) {
        return engine.placeOrder(account, INSTRUMENT, side, d(price), d(quantity));
    }

    private static void balance(Account account, Asset asset, String available, String reserved) {
        Balance balance = account.balanceOf(asset);
        assertEquals(0, d(available).compareTo(balance.available()));
        assertEquals(0, d(reserved).compareTo(balance.reserved()));
    }

    @Test
    void buyWithoutLiquidityReservesQuoteAndRests() {
        Account buyer = account("buyer", "0", "1000");
        PlacementResult result = place("buyer", OrderSide.BUY, "500.00", "1.0");
        assertEquals(OrderStatus.OPEN, result.status());
        assertEquals(d("1.0"), result.remainingQuantity());
        assertTrue(result.trades().isEmpty());
        balance(buyer, BRL, "500", "500");
        assertSame(engine.orderById(result.orderId()), engine.bookFor(INSTRUMENT).bestBid());
        assertNull(engine.bookFor(INSTRUMENT).bestAsk());
    }

    @Test
    void sellWithoutLiquidityReservesBaseAndRests() {
        Account seller = account("seller", "2", "0");
        PlacementResult result = place("seller", OrderSide.SELL, "500", "1");
        assertEquals(OrderStatus.OPEN, result.status());
        balance(seller, BTC, "1", "1");
        assertSame(engine.orderById(result.orderId()), engine.bookFor(INSTRUMENT).bestAsk());
        assertNull(engine.bookFor(INSTRUMENT).bestBid());
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void insufficientBalanceDoesNotConsumeIdsSequencesOrMutateState(OrderSide side) {
        Account account = account("account", "0", "0");
        var before = account.balances();
        assertThrows(IllegalArgumentException.class, () -> place("account", side, "500", "1"));
        assertEquals(before, account.balances());
        assertNull(engine.orderById(1));
        assertNull(engine.bookFor(INSTRUMENT).bestBid());
        assertNull(engine.bookFor(INSTRUMENT).bestAsk());
        account.creditAvailable(side == OrderSide.BUY ? BRL : BTC, side == OrderSide.BUY ? d("500") : d("1"));
        PlacementResult accepted = place("account", side, "500", "1");
        assertEquals(1, accepted.orderId());
        assertEquals(1, engine.orderById(1).sequence());
    }

    @Test
    void fullBuyMatchSettlesAndRetainsFilledOrdersWithoutBookEntries() {
        Account seller = account("seller", "1", "0");
        Account buyer = account("buyer", "0", "500");
        PlacementResult sell = place("seller", OrderSide.SELL, "500", "1");
        PlacementResult buy = place("buyer", OrderSide.BUY, "500", "1");
        assertEquals(OrderStatus.FILLED, buy.status());
        assertEquals(OrderStatus.FILLED, engine.orderById(sell.orderId()).status());
        assertEquals(OrderStatus.FILLED, engine.orderById(buy.orderId()).status());
        assertEquals(1, buy.trades().size());
        assertEquals(buy.orderId(), buy.trades().getFirst().buyOrderId());
        assertEquals(sell.orderId(), buy.trades().getFirst().sellOrderId());
        balance(buyer, BTC, "1", "0");
        balance(buyer, BRL, "0", "0");
        balance(seller, BTC, "0", "0");
        balance(seller, BRL, "500", "0");
        assertNull(engine.bookFor(INSTRUMENT).bestBid());
        assertNull(engine.bookFor(INSTRUMENT).bestAsk());
    }

    @Test
    void priceImprovementIsReleasedImmediately() {
        Account seller = account("seller", "1", "0");
        Account buyer = account("buyer", "0", "500");
        place("seller", OrderSide.SELL, "490", "1");
        PlacementResult buy = place("buyer", OrderSide.BUY, "500", "1");
        assertEquals(0, d("490").compareTo(buy.trades().getFirst().executionPrice()));
        balance(buyer, BRL, "10", "0");
        balance(buyer, BTC, "1", "0");
        balance(seller, BRL, "490", "0");
    }

    @Test
    void incomingSellSettlesAgainstRestingBuyAtBuyPrice() {
        Account buyer = account("buyer", "0", "500");
        Account seller = account("seller", "1", "0");
        PlacementResult buy = place("buyer", OrderSide.BUY, "500", "1");
        PlacementResult sell = place("seller", OrderSide.SELL, "490", "1");
        Trade trade = sell.trades().getFirst();
        assertEquals(buy.orderId(), trade.buyOrderId());
        assertEquals(sell.orderId(), trade.sellOrderId());
        assertEquals(0, d("500").compareTo(trade.executionPrice()));
        balance(buyer, BRL, "0", "0");
        balance(buyer, BTC, "1", "0");
        balance(seller, BTC, "0", "0");
        balance(seller, BRL, "500", "0");
    }

    @Test
    void partialBuyRestsWithOnlyRemainingLimitReservation() {
        Account seller = account("seller", "0.4", "0");
        Account buyer = account("buyer", "0", "500");
        place("seller", OrderSide.SELL, "490", "0.4");
        PlacementResult buy = place("buyer", OrderSide.BUY, "500", "1");
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.status());
        assertEquals(0, d("0.6").compareTo(buy.remainingQuantity()));
        balance(buyer, BRL, "4", "300");
        balance(buyer, BTC, "0.4", "0");
        balance(seller, BTC, "0", "0");
        balance(seller, BRL, "196", "0");
        assertSame(engine.orderById(buy.orderId()), engine.bookFor(INSTRUMENT).bestBid());
    }

    @Test
    void partialSellRestsAndRestingBuyRetainsCorrectReservation() {
        Account buyer = account("buyer", "0", "200");
        Account seller = account("seller", "1", "0");
        place("buyer", OrderSide.BUY, "500", "0.4");
        PlacementResult sell = place("seller", OrderSide.SELL, "490", "1");
        assertEquals(OrderStatus.PARTIALLY_FILLED, sell.status());
        balance(seller, BTC, "0", "0.6");
        balance(seller, BRL, "200", "0");
        balance(buyer, BTC, "0.4", "0");
        balance(buyer, BRL, "0", "0");
        assertEquals(sell.orderId(), engine.bookFor(INSTRUMENT).bestAsk().id());
    }

    @Test
    void multipleMatchesSettleInPriceTimeOrderAndLeaveNonCrossingLiquidity() {
        Account worse = account("worse", "0.4", "0");
        Account first = account("first", "0.3", "0");
        Account second = account("second", "0.2", "0");
        Account outside = account("outside", "1", "0");
        Account buyer = account("buyer", "0", "1000");
        PlacementResult worseOrder = place("worse", OrderSide.SELL, "500", "0.4");
        PlacementResult firstOrder = place("first", OrderSide.SELL, "490.0", "0.3");
        PlacementResult secondOrder = place("second", OrderSide.SELL, "490.00", "0.2");
        PlacementResult outsideOrder = place("outside", OrderSide.SELL, "501", "1");
        PlacementResult buy = place("buyer", OrderSide.BUY, "500", "2");
        assertEquals(3, buy.trades().size());
        assertEquals(firstOrder.orderId(), buy.trades().get(0).sellOrderId());
        assertEquals(secondOrder.orderId(), buy.trades().get(1).sellOrderId());
        assertEquals(worseOrder.orderId(), buy.trades().get(2).sellOrderId());
        balance(first, BRL, "147", "0");
        balance(second, BRL, "98", "0");
        balance(worse, BRL, "200", "0");
        balance(buyer, BRL, "5", "550");
        balance(buyer, BTC, "0.9", "0");
        balance(outside, BTC, "0", "1");
        assertEquals(outsideOrder.orderId(), engine.bookFor(INSTRUMENT).bestAsk().id());
        assertEquals(buy.orderId(), engine.bookFor(INSTRUMENT).bestBid().id());
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void selfTradeBeforeExecutionCancelsIncomingAndLeavesRestingUntouched(OrderSide incomingSide) {
        Account owner = account("owner", "2", "1000");
        Account later = account("later", "2", "1000");
        OrderSide restingSide = incomingSide == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        PlacementResult self = place("owner", restingSide, "500", "1");
        PlacementResult other = place("later", restingSide, "500", "1");
        var before = owner.balances();
        PlacementResult incoming = place("owner", incomingSide, "500", "1");
        assertEquals(OrderStatus.CANCELLED, incoming.status());
        assertTrue(incoming.trades().isEmpty());
        assertEquals(0, d("1").compareTo(incoming.remainingQuantity()));
        assertEquals(before, owner.balances());
        assertEquals(OrderStatus.OPEN, engine.orderById(self.orderId()).status());
        assertEquals(OrderStatus.OPEN, engine.orderById(other.orderId()).status());
        assertEquals(OrderStatus.CANCELLED, engine.orderById(incoming.orderId()).status());
        assertNull(engine.bookFor(INSTRUMENT).orderById(incoming.orderId()));
        assertEquals(self.orderId(), (incomingSide == OrderSide.BUY ? engine.bookFor(INSTRUMENT).bestAsk()
                : engine.bookFor(INSTRUMENT).bestBid()).id());
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void selfTradeAfterExecutionPreservesPriorSettlementAndReleasesOnlyRemainder(OrderSide side) {
        Account owner = account("owner", "2", "1000");
        Account other = account("other", "2", "1000");
        OrderSide opposite = side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        place("other", opposite, side == OrderSide.BUY ? "490" : "510", "0.4");
        PlacementResult self = place("owner", opposite, "500", "1");
        Order selfOrder = engine.orderById(self.orderId());
        BigDecimal selfRemaining = selfOrder.remainingQuantity();
        PlacementResult incoming = place("owner", side, "500", "1");
        assertEquals(OrderStatus.CANCELLED, incoming.status());
        assertEquals(1, incoming.trades().size());
        assertEquals(0, d("0.6").compareTo(incoming.remainingQuantity()));
        assertSame(selfOrder, engine.bookFor(INSTRUMENT).orderById(self.orderId()));
        assertEquals(selfRemaining, selfOrder.remainingQuantity());
        assertEquals(OrderStatus.OPEN, selfOrder.status());
        assertNull(engine.bookFor(INSTRUMENT).orderById(incoming.orderId()));
        if (side == OrderSide.BUY) {
            balance(owner, BTC, "1.4", "1");
            balance(owner, BRL, "804", "0");
            balance(other, BTC, "1.6", "0");
            balance(other, BRL, "1196", "0");
        } else {
            balance(owner, BTC, "1.6", "0");
            balance(owner, BRL, "704", "500");
            balance(other, BTC, "2.4", "0");
            balance(other, BRL, "796", "0");
        }
    }

    @Test
    void partiallyFilledRestingBuyKeepsReservationAndPriority() {
        Account buyer = account("buyer", "0", "1000");
        Account later = account("later", "0", "500");
        account("seller", "0.4", "0");
        PlacementResult first = place("buyer", OrderSide.BUY, "500", "1");
        place("later", OrderSide.BUY, "500", "1");
        place("seller", OrderSide.SELL, "490", "0.4");
        assertEquals(first.orderId(), engine.bookFor(INSTRUMENT).bestBid().id());
        assertEquals(OrderStatus.PARTIALLY_FILLED, engine.orderById(first.orderId()).status());
        balance(buyer, BRL, "500", "300");
        balance(buyer, BTC, "0.4", "0");
        balance(later, BRL, "0", "500");
    }

    @Test
    void invalidInputsDoNotMutateStateOrConsumeCounters() {
        Account funded = account("funded", "2", "1000");
        var before = funded.balances();
        Instrument unknown = new Instrument(new Asset("ETH"), BRL);
        List<Runnable> invalid = List.of(
                () -> engine.placeOrder(null, INSTRUMENT, OrderSide.BUY, d("500"), d("1")),
                () -> engine.placeOrder(" ", INSTRUMENT, OrderSide.BUY, d("500"), d("1")),
                () -> engine.placeOrder("unknown", INSTRUMENT, OrderSide.BUY, d("500"), d("1")),
                () -> engine.placeOrder("funded", null, OrderSide.BUY, d("500"), d("1")),
                () -> engine.placeOrder("funded", unknown, OrderSide.BUY, d("500"), d("1")),
                () -> engine.placeOrder("funded", INSTRUMENT, null, d("500"), d("1")),
                () -> engine.placeOrder("funded", INSTRUMENT, OrderSide.BUY, null, d("1")),
                () -> engine.placeOrder("funded", INSTRUMENT, OrderSide.BUY, d("500"), null),
                () -> place("funded", OrderSide.BUY, "0", "1"),
                () -> place("funded", OrderSide.BUY, "-1", "1"),
                () -> place("funded", OrderSide.BUY, "500", "0"),
                () -> place("funded", OrderSide.BUY, "500", "-1"));
        for (Runnable request : invalid) {
            assertThrows(RuntimeException.class, request::run);
            assertEquals(before, funded.balances());
            assertNull(engine.orderById(1));
            assertNull(engine.bookFor(INSTRUMENT).bestBid());
            assertNull(engine.bookFor(INSTRUMENT).bestAsk());
        }
        assertEquals(1, place("funded", OrderSide.BUY, "500", "1").orderId());
        assertEquals(1, engine.orderById(1).sequence());
        assertEquals(2, place("funded", OrderSide.BUY, "400", "1").orderId());
        assertEquals(2, engine.orderById(2).sequence());
    }

    @Test
    void duplicateRegistrationIsExplicitAndPreservesExistingAccountAndBook() {
        Account original = account("same", "0", "500");
        PlacementResult buy = place("same", OrderSide.BUY, "500", "1");
        OrderBook book = engine.bookFor(INSTRUMENT);
        assertThrows(IllegalArgumentException.class, () -> engine.registerAccount(new Account("same")));
        assertThrows(IllegalArgumentException.class, () -> engine.registerInstrument(new Instrument(new Asset("BTC"), new Asset("BRL"))));
        assertThrows(NullPointerException.class, () -> engine.registerAccount(null));
        assertThrows(NullPointerException.class, () -> engine.registerInstrument(null));
        assertSame(book, engine.bookFor(INSTRUMENT));
        assertEquals(buy.orderId(), book.bestBid().id());
        account("seller", "1", "0");
        place("seller", OrderSide.SELL, "500", "1");
        balance(original, BTC, "1", "0");
    }

    @Test
    void placementResultsAreSnapshotsAndTradeListsAreProtected() {
        account("seller", "1", "0");
        account("buyer", "0", "500");
        PlacementResult resting = place("seller", OrderSide.SELL, "500", "1");
        PlacementResult filled = place("buyer", OrderSide.BUY, "500", "1");
        assertEquals(OrderStatus.OPEN, resting.status());
        assertEquals(0, d("1").compareTo(resting.remainingQuantity()));
        assertTrue(resting.trades().isEmpty());
        assertThrows(UnsupportedOperationException.class, filled.trades()::clear);
        List<Trade> mutable = new ArrayList<>(filled.trades());
        PlacementResult copied = new PlacementResult(filled.orderId(), filled.status(), filled.remainingQuantity(), mutable);
        mutable.clear();
        assertEquals(1, copied.trades().size());
    }
}
