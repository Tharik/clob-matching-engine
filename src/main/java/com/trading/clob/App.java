package com.trading.clob;

import com.trading.clob.domain.Account;
import com.trading.clob.domain.Asset;
import com.trading.clob.domain.Balance;
import com.trading.clob.domain.Instrument;
import com.trading.clob.domain.OrderSide;
import com.trading.clob.domain.Trade;
import com.trading.clob.engine.BookOrderView;
import com.trading.clob.engine.OrderBookSnapshot;
import com.trading.clob.engine.PlacementResult;
import com.trading.clob.engine.TradingEngine;

import java.math.BigDecimal;
import java.util.List;

/** Deterministic demonstration using only the engine's public API. */
public class App {
    public static void main(String[] args) {
        Asset btc = new Asset("BTC");
        Asset brl = new Asset("BRL");
        Instrument instrument = new Instrument(btc, brl);
        TradingEngine engine = new TradingEngine();
        Account alice = new Account("Alice");
        alice.creditAvailable(brl, decimal("500000"));
        Account bob = new Account("Bob");
        bob.creditAvailable(btc, decimal("1"));
        engine.registerInstrument(instrument);
        engine.registerAccount(alice);
        engine.registerAccount(bob);

        System.out.println("=== CLOB Matching Engine Demo ===");
        System.out.println();
        System.out.println("--- Scenario 1: Matching ---");
        System.out.println("Bob submits SELL 1 BTC @ 490000 BRL");
        PlacementResult sell = engine.placeOrder(bob.id(), instrument, OrderSide.SELL,
                decimal("490000"), decimal("1"));
        printResult("Bob's SELL", sell);
        System.out.println("Alice submits BUY 1 BTC @ 500000 BRL");
        PlacementResult buy = engine.placeOrder(alice.id(), instrument, OrderSide.BUY,
                decimal("500000"), decimal("1"));
        for (Trade trade : buy.trades()) {
            System.out.println("Trade: BUY order " + trade.buyOrderId() + " / SELL order " + trade.sellOrderId());
            System.out.println("  Execution price: " + trade.executionPrice().toPlainString() + " BRL/BTC");
            System.out.println("  Executed quantity: " + trade.executedQuantity().toPlainString() + " BTC");
        }
        printResult("Alice's BUY", buy);
        System.out.println("Final balances:");
        printBalances(alice, btc, brl);
        printBalances(bob, btc, brl);

        System.out.println();
        System.out.println("--- Scenario 2: Cancellation ---");
        Account charlie = new Account("Charlie");
        charlie.creditAvailable(brl, decimal("400000"));
        engine.registerAccount(charlie);
        System.out.println("Charlie submits BUY 1 BTC @ 400000 BRL");
        PlacementResult resting = engine.placeOrder(charlie.id(), instrument, OrderSide.BUY,
                decimal("400000"), decimal("1"));
        printResult("Charlie's BUY", resting);
        System.out.println("After placement:");
        printBalances(charlie, btc, brl);
        printOrderBook(engine.orderBookSnapshot(instrument));
        engine.cancelOrder(charlie.id(), resting.orderId());
        System.out.println("Charlie cancels BUY order " + resting.orderId());
        System.out.println("After cancellation:");
        printBalances(charlie, btc, brl);
        printOrderBook(engine.orderBookSnapshot(instrument));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static void printResult(String label, PlacementResult result) {
        System.out.println(label + ": order " + result.orderId() + ", status " + result.status()
                + ", remaining quantity " + result.remainingQuantity().toPlainString() + " BTC");
    }

    private static void printOrderBook(OrderBookSnapshot snapshot) {
        System.out.println("Order book:");
        printBookSide("BIDS", snapshot.bids(), snapshot.instrument());
        printBookSide("ASKS", snapshot.asks(), snapshot.instrument());
    }

    private static void printBookSide(String label, List<BookOrderView> orders, Instrument instrument) {
        System.out.println("  " + label + ":");
        if (orders.isEmpty()) {
            System.out.println("    empty");
        } else {
            for (BookOrderView order : orders) {
                System.out.println("    order " + order.orderId() + ": " + order.remainingQuantity().toPlainString()
                        + " " + instrument.baseAsset().code() + " @ " + order.limitPrice().toPlainString()
                        + " " + instrument.quoteAsset().code());
            }
        }
    }

    private static void printBalances(Account account, Asset... assets) {
        System.out.println(account.id() + ":");
        for (Asset asset : assets) {
            Balance balance = account.balanceOf(asset);
            System.out.println("  " + asset.code() + ": available " + balance.available().toPlainString()
                    + ", reserved " + balance.reserved().toPlainString());
        }
    }
}
