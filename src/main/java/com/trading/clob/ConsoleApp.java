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

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Scanner;

/** Interactive terminal adapter; all engine commands run serially on the calling thread. */
public final class ConsoleApp {
    private final Scanner input;
    private final PrintStream output;
    private final Asset btc = new Asset("BTC");
    private final Asset brl = new Asset("BRL");
    private final Instrument market = new Instrument(btc, brl);
    private final TradingEngine engine = new TradingEngine();
    private final Map<String, Account> accounts = new HashMap<>();

    public ConsoleApp(Scanner input, PrintStream output) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        engine.registerInstrument(market);
    }

    public static void main(String[] args) {
        new ConsoleApp(new Scanner(System.in), System.out).run();
    }

    public void run() {
        output.println("=== CLOB Interactive Console ===");
        output.println("Market: BTC/BRL");
        while (true) {
            printMenu();
            try {
                switch (read("Choice:")) {
                    case "0" -> { output.println("Goodbye."); return; }
                    case "1" -> createAccount();
                    case "2" -> changeAvailable(true);
                    case "3" -> changeAvailable(false);
                    case "4" -> showBalances();
                    case "5" -> placeOrder();
                    case "6" -> cancelOrder();
                    case "7" -> showBook();
                    default -> output.println("Error: Invalid menu option.");
                }
            } catch (IllegalArgumentException failure) {
                output.println("Error: " + failure.getMessage());
            } catch (NoSuchElementException endOfInput) {
                return;
            }
        }
    }

    private void printMenu() {
        output.println();
        output.println("1 - Create account");
        output.println("2 - Credit asset");
        output.println("3 - Debit asset");
        output.println("4 - Show account balances");
        output.println("5 - Place limit order");
        output.println("6 - Cancel order");
        output.println("7 - Show order book");
        output.println("0 - Exit");
    }

    private String read(String prompt) {
        output.println(prompt);
        return input.nextLine().trim();
    }

    private Account readAccount() {
        String id = read("Account ID:");
        if (id.isBlank()) throw new IllegalArgumentException("Account ID must not be blank");
        Account account = accounts.get(id);
        if (account == null) throw new IllegalArgumentException("Unknown account: " + id);
        return account;
    }

    private Asset readAsset() {
        return switch (read("Asset (BTC/BRL):").toUpperCase(Locale.ROOT)) {
            case "BTC" -> btc;
            case "BRL" -> brl;
            default -> throw new IllegalArgumentException("Asset must be BTC or BRL");
        };
    }

    private BigDecimal readDecimal(String prompt) {
        String value = read(prompt);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid decimal: '" + value + "' (use a dot for decimals)");
        }
    }

    private void createAccount() {
        Account account = new Account(read("Account ID:"));
        engine.registerAccount(account);
        accounts.put(account.id(), account);
        output.println("Account '" + account.id() + "' created.");
    }

    private void changeAvailable(boolean credit) {
        Account account = readAccount();
        Asset asset = readAsset();
        BigDecimal amount = readDecimal("Amount:");
        if (credit) account.creditAvailable(asset, amount);
        else account.debitAvailable(asset, amount);
        output.println((credit ? "Credited " : "Debited ") + amount.toPlainString() + " " + asset.code()
                + (credit ? " to " : " from ") + account.id() + ".");
        printBalance(account, asset, "");
    }

    private void showBalances() {
        Account account = readAccount();
        output.println(account.id() + ":");
        printBalance(account, btc, "  ");
        printBalance(account, brl, "  ");
    }

    private void printBalance(Account account, Asset asset, String indent) {
        Balance balance = account.balanceOf(asset);
        output.println(indent + asset.code() + ": available " + balance.available().toPlainString()
                + ", reserved " + balance.reserved().toPlainString());
    }

    private void placeOrder() {
        String accountId = read("Account ID:");
        OrderSide side;
        try {
            side = OrderSide.valueOf(read("Side (BUY/SELL):").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Side must be BUY or SELL");
        }
        BigDecimal price = readDecimal("Limit price (BRL per BTC):");
        BigDecimal quantity = readDecimal("Quantity (BTC):");
        PlacementResult result = engine.placeOrder(accountId, market, side, price, quantity);
        output.println("Order result:");
        output.println("  ID: " + result.orderId());
        output.println("  Side: " + side);
        output.println("  Status: " + result.status());
        output.println("  Limit price: " + price.toPlainString() + " BRL/BTC");
        output.println("  Remaining: " + result.remainingQuantity().toPlainString() + " BTC");
        for (Trade trade : result.trades()) {
            output.println("Trade:");
            output.println("  BUY order: " + trade.buyOrderId());
            output.println("  SELL order: " + trade.sellOrderId());
            output.println("  Price: " + trade.executionPrice().toPlainString() + " BRL/BTC");
            output.println("  Quantity: " + trade.executedQuantity().toPlainString() + " BTC");
        }
    }

    private void cancelOrder() {
        String accountId = read("Account ID:");
        long orderId;
        try {
            orderId = Long.parseLong(read("Order ID:"));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Order ID must be a whole number within the long range");
        }
        engine.cancelOrder(accountId, orderId);
        output.println("Order " + orderId + " cancelled.");
    }

    private void showBook() {
        OrderBookSnapshot snapshot = engine.orderBookSnapshot(market);
        output.println("BTC/BRL Order Book");
        printSide("BIDS", snapshot.bids());
        printSide("ASKS", snapshot.asks());
    }

    private void printSide(String label, List<BookOrderView> orders) {
        output.println(label + ":");
        if (orders.isEmpty()) output.println("  empty");
        for (BookOrderView order : orders) {
            output.println("  #" + order.orderId() + "  " + order.remainingQuantity().toPlainString()
                    + " BTC @ " + order.limitPrice().toPlainString() + " BRL");
        }
    }
}
