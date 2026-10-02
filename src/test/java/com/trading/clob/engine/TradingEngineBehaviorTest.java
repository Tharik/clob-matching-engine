package com.trading.clob.engine;

import com.trading.clob.domain.Account;
import com.trading.clob.domain.Asset;
import com.trading.clob.domain.Balance;
import com.trading.clob.domain.Instrument;
import com.trading.clob.domain.Order;
import com.trading.clob.domain.OrderSide;
import com.trading.clob.domain.OrderStatus;
import com.trading.clob.domain.Trade;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class TradingEngineBehaviorTest {
    private static final Asset BTC = new Asset("BTC");
    private static final Asset ETH = new Asset("ETH");
    private static final Asset BRL = new Asset("BRL");
    private static final Instrument BTC_BRL = new Instrument(BTC, BRL);
    private static final Instrument ETH_BRL = new Instrument(ETH, BRL);
    private final TradingEngine engine = new TradingEngine();

    TradingEngineBehaviorTest() {
        engine.registerInstrument(BTC_BRL);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }

    private Account account(String id, String btc, String brl) {
        Account account = new Account(id);
        if (decimal(btc).signum() > 0) account.creditAvailable(BTC, decimal(btc));
        if (decimal(brl).signum() > 0) account.creditAvailable(BRL, decimal(brl));
        engine.registerAccount(account);
        return account;
    }

    private PlacementResult place(Account account, Instrument instrument, OrderSide side,
                                  String price, String quantity) {
        return engine.placeOrder(account.id(), instrument, side, decimal(price), decimal(quantity));
    }

    private static void numeric(String expected, BigDecimal actual) {
        assertEquals(0, decimal(expected).compareTo(actual));
    }

    private static void balance(Account account, Asset asset, String available, String reserved) {
        Balance balance = account.balanceOf(asset);
        numeric(available, balance.available());
        numeric(reserved, balance.reserved());
    }

    private static BigDecimal total(List<Account> accounts, Asset asset) {
        BigDecimal total = BigDecimal.ZERO;
        for (Account account : accounts) {
            Balance balance = account.balanceOf(asset);
            total = total.add(balance.available()).add(balance.reserved());
        }
        return total;
    }

    @Test
    void instrumentsKeepBooksIndependentWhileSharingAccountQuoteReservations() {
        engine.registerInstrument(ETH_BRL);
        Account buyer = account("buyer", "0", "1000");
        Account ethSeller = account("ethSeller", "0", "0");
        ethSeller.creditAvailable(ETH, decimal("2"));
        Account btcSeller = account("btcSeller", "1", "0");

        PlacementResult btcBid = place(buyer, BTC_BRL, OrderSide.BUY, "500", "1");
        // This ask would cross the BTC bid if instrument isolation were broken.
        PlacementResult ethAsk = place(ethSeller, ETH_BRL, OrderSide.SELL, "490", "1");
        PlacementResult ethBid = place(buyer, ETH_BRL, OrderSide.BUY, "200", "1");
        assertTrue(btcBid.trades().isEmpty());
        assertTrue(ethAsk.trades().isEmpty());
        assertTrue(ethBid.trades().isEmpty());
        balance(buyer, BRL, "300", "700");
        balance(ethSeller, ETH, "1", "1");
        assertNotSame(engine.bookFor(BTC_BRL), engine.bookFor(ETH_BRL));
        assertEquals(btcBid.orderId(), engine.bookFor(BTC_BRL).bestBid().id());
        assertNull(engine.bookFor(BTC_BRL).bestAsk());
        assertEquals(ethBid.orderId(), engine.bookFor(ETH_BRL).bestBid().id());
        assertEquals(ethAsk.orderId(), engine.bookFor(ETH_BRL).bestAsk().id());
        assertNull(engine.bookFor(BTC_BRL).orderById(ethBid.orderId()));
        assertNull(engine.bookFor(ETH_BRL).orderById(btcBid.orderId()));

        var sellerBeforeCancellation = ethSeller.balances();
        engine.cancelOrder(buyer.id(), btcBid.orderId());
        balance(buyer, BRL, "800", "200");
        assertEquals(OrderStatus.CANCELLED, engine.orderById(btcBid.orderId()).status());
        assertNull(engine.bookFor(BTC_BRL).bestBid());
        assertEquals(OrderStatus.OPEN, engine.orderById(ethBid.orderId()).status());
        numeric("1", engine.orderById(ethBid.orderId()).remainingQuantity());
        assertEquals(ethBid.orderId(), engine.bookFor(ETH_BRL).bestBid().id());
        assertEquals(ethAsk.orderId(), engine.bookFor(ETH_BRL).bestAsk().id());
        assertEquals(sellerBeforeCancellation, ethSeller.balances());

        PlacementResult ethExecution = place(ethSeller, ETH_BRL, OrderSide.SELL, "200", "0.5");
        assertEquals(1, ethExecution.trades().size());
        assertEquals(ETH_BRL, ethExecution.trades().getFirst().instrument());
        assertEquals(ethBid.orderId(), ethExecution.trades().getFirst().buyOrderId());
        balance(buyer, BRL, "800", "100");
        balance(buyer, ETH, "0.5", "0");
        balance(buyer, BTC, "0", "0");
        PlacementResult btcAsk = place(btcSeller, BTC_BRL, OrderSide.SELL, "190", "1");
        assertTrue(btcAsk.trades().isEmpty());
        assertEquals(OrderStatus.OPEN, btcAsk.status());
        assertEquals(ethBid.orderId(), engine.bookFor(ETH_BRL).bestBid().id());
        numeric("0.5", engine.bookFor(ETH_BRL).bestBid().remainingQuantity());
        assertEquals(ethAsk.orderId(), engine.bookFor(ETH_BRL).bestAsk().id());
        var ids = new HashSet<Long>();
        for (PlacementResult result : List.of(btcBid, ethAsk, ethBid, ethExecution, btcAsk)) {
            assertTrue(ids.add(result.orderId()), "Accepted order IDs must be globally unique");
        }
    }

    @Test
    void assetsAreConservedAcrossReservationsTradesRefundsAndRemainderCancellation() {
        Account buyer = account("buyer", "0", "1000");
        Account firstSeller = account("firstSeller", "1.2", "0");
        Account secondSeller = account("secondSeller", "0.8", "0");
        List<Account> accounts = List.of(buyer, firstSeller, secondSeller);
        BigDecimal initialBase = total(accounts, BTC);
        BigDecimal initialQuote = total(accounts, BRL);
        place(firstSeller, BTC_BRL, OrderSide.SELL, "490", "0.6");
        place(secondSeller, BTC_BRL, OrderSide.SELL, "495", "0.4");
        assertEquals(0, initialBase.compareTo(total(accounts, BTC)));
        assertEquals(0, initialQuote.compareTo(total(accounts, BRL)));

        PlacementResult buy = place(buyer, BTC_BRL, OrderSide.BUY, "500", "1.4");
        assertEquals(2, buy.trades().size());
        numeric("490", buy.trades().get(0).executionPrice());
        numeric("495", buy.trades().get(1).executionPrice());
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.status());
        numeric("0.4", buy.remainingQuantity());
        balance(buyer, BTC, "1", "0");
        // Actual cost 492, remaining reservation 200, price-improvement refund 8.
        balance(buyer, BRL, "308", "200");
        balance(firstSeller, BRL, "294", "0");
        balance(secondSeller, BRL, "198", "0");
        assertEquals(0, initialBase.compareTo(total(accounts, BTC)));
        assertEquals(0, initialQuote.compareTo(total(accounts, BRL)));

        engine.cancelOrder(buyer.id(), buy.orderId());
        assertEquals(OrderStatus.CANCELLED, engine.orderById(buy.orderId()).status());
        balance(buyer, BRL, "508", "0");
        balance(firstSeller, BTC, "0.6", "0");
        balance(secondSeller, BTC, "0.4", "0");
        assertNull(engine.bookFor(BTC_BRL).bestBid());
        assertEquals(0, initialBase.compareTo(total(accounts, BTC)));
        assertEquals(0, initialQuote.compareTo(total(accounts, BRL)));
    }

    @Test
    void incomingSellSweepsHighestBidsInFifoOrderAndRestsBeforeNonCrossingLiquidity() {
        Account worse = account("worse", "0", "500");
        Account first = account("first", "0", "500");
        Account second = account("second", "0", "500");
        Account outside = account("outside", "0", "500");
        Account seller = account("seller", "3", "0");
        PlacementResult worseBid = place(worse, BTC_BRL, OrderSide.BUY, "500", "0.4");
        PlacementResult firstBid = place(first, BTC_BRL, OrderSide.BUY, "510.0", "0.3");
        PlacementResult secondBid = place(second, BTC_BRL, OrderSide.BUY, "510.00", "0.2");
        PlacementResult outsideBid = place(outside, BTC_BRL, OrderSide.BUY, "499", "1");
        var outsideBefore = outside.balances();
        PlacementResult sell = place(seller, BTC_BRL, OrderSide.SELL, "500", "2");
        assertEquals(3, sell.trades().size());
        long[] expectedBuyIds = {firstBid.orderId(), secondBid.orderId(), worseBid.orderId()};
        String[] expectedPrices = {"510", "510", "500"};
        String[] expectedQuantities = {"0.3", "0.2", "0.4"};
        for (int i = 0; i < expectedBuyIds.length; i++) {
            Trade trade = sell.trades().get(i);
            assertEquals(expectedBuyIds[i], trade.buyOrderId());
            assertEquals(sell.orderId(), trade.sellOrderId());
            numeric(expectedPrices[i], trade.executionPrice());
            numeric(expectedQuantities[i], trade.executedQuantity());
            assertEquals(OrderStatus.FILLED, engine.orderById(expectedBuyIds[i]).status());
            assertNull(engine.bookFor(BTC_BRL).orderById(expectedBuyIds[i]));
        }
        balance(first, BTC, "0.3", "0");
        balance(first, BRL, "347", "0");
        balance(second, BTC, "0.2", "0");
        balance(second, BRL, "398", "0");
        balance(worse, BTC, "0.4", "0");
        balance(worse, BRL, "300", "0");
        balance(seller, BTC, "1", "1.1");
        balance(seller, BRL, "455", "0");
        assertEquals(OrderStatus.PARTIALLY_FILLED, sell.status());
        numeric("1.1", sell.remainingQuantity());
        assertEquals(sell.orderId(), engine.bookFor(BTC_BRL).bestAsk().id());
        assertEquals(outsideBefore, outside.balances());
        assertEquals(outsideBid.orderId(), engine.bookFor(BTC_BRL).bestBid().id());
        assertEquals(OrderStatus.OPEN, engine.orderById(outsideBid.orderId()).status());
        numeric("1", engine.orderById(outsideBid.orderId()).remainingQuantity());
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void partiallyFilledIncomingRemainderKeepsPriorityOverLaterSamePriceOrder(OrderSide side) {
        Account initialLiquidity = account("initialLiquidity", "2", "1000");
        Account earlierAccount = account("earlier", "2", "1000");
        Account laterAccount = account("later", "2", "1000");
        Account finalLiquidity = account("finalLiquidity", "2", "1000");
        OrderSide opposite = side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        place(initialLiquidity, BTC_BRL, opposite, side == OrderSide.BUY ? "490" : "510", "0.4");
        PlacementResult earlier = place(earlierAccount, BTC_BRL, side, "500.0", "1");
        Order earlierOrder = engine.orderById(earlier.orderId());
        long originalSequence = earlierOrder.sequence();
        assertEquals(OrderStatus.PARTIALLY_FILLED, earlier.status());
        numeric("0.6", earlier.remainingQuantity());
        balance(earlierAccount, side == OrderSide.BUY ? BRL : BTC,
                side == OrderSide.BUY ? "504" : "1", side == OrderSide.BUY ? "300" : "0.6");
        PlacementResult later = place(laterAccount, BTC_BRL, side, "500.00", "1");
        assertTrue(later.trades().isEmpty());
        assertTrue(originalSequence < engine.orderById(later.orderId()).sequence());
        balance(laterAccount, side == OrderSide.BUY ? BRL : BTC,
                side == OrderSide.BUY ? "500" : "1", side == OrderSide.BUY ? "500" : "1");
        assertEquals(earlier.orderId(), (side == OrderSide.BUY ? engine.bookFor(BTC_BRL).bestBid()
                : engine.bookFor(BTC_BRL).bestAsk()).id());

        PlacementResult finalOrder = place(finalLiquidity, BTC_BRL, opposite, "500", "0.8");
        assertEquals(OrderStatus.FILLED, finalOrder.status());
        assertEquals(2, finalOrder.trades().size());
        for (int i = 0; i < 2; i++) {
            Trade trade = finalOrder.trades().get(i);
            assertEquals(i == 0 ? earlier.orderId() : later.orderId(),
                    side == OrderSide.BUY ? trade.buyOrderId() : trade.sellOrderId());
            assertEquals(finalOrder.orderId(), side == OrderSide.BUY ? trade.sellOrderId() : trade.buyOrderId());
            numeric("500", trade.executionPrice());
            numeric(i == 0 ? "0.6" : "0.2", trade.executedQuantity());
        }
        assertEquals(OrderStatus.FILLED, earlierOrder.status());
        numeric("0", earlierOrder.remainingQuantity());
        numeric("1", earlierOrder.originalQuantity());
        assertEquals(originalSequence, earlierOrder.sequence());
        assertNull(engine.bookFor(BTC_BRL).orderById(earlier.orderId()));
        assertEquals(OrderStatus.PARTIALLY_FILLED, engine.orderById(later.orderId()).status());
        numeric("0.8", engine.orderById(later.orderId()).remainingQuantity());
        assertEquals(later.orderId(), (side == OrderSide.BUY ? engine.bookFor(BTC_BRL).bestBid()
                : engine.bookFor(BTC_BRL).bestAsk()).id());
        if (side == OrderSide.BUY) {
            balance(earlierAccount, BRL, "504", "0");
            balance(earlierAccount, BTC, "3", "0");
            balance(laterAccount, BRL, "500", "400");
            balance(laterAccount, BTC, "2.2", "0");
            balance(finalLiquidity, BTC, "1.2", "0");
            balance(finalLiquidity, BRL, "1400", "0");
        } else {
            balance(earlierAccount, BTC, "1", "0");
            balance(earlierAccount, BRL, "1504", "0");
            balance(laterAccount, BTC, "1", "0.8");
            balance(laterAccount, BRL, "1100", "0");
            balance(finalLiquidity, BTC, "2.8", "0");
            balance(finalLiquidity, BRL, "600", "0");
        }
    }
}
