package com.trading.clob;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleAppTest {
    private static String run(String commands) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Scanner input = new Scanner(commands);
             PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new ConsoleApp(input, output).run();
        }
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void createCreditDebitAndShowBalances() {
        String output = run("""
                1
                Alice
                2
                Alice
                BRL
                500000
                3
                Alice
                BRL
                10000
                4
                Alice
                0
                """);
        assertTrue(output.contains("Account 'Alice' created."));
        assertTrue(output.contains("Credited 500000 BRL to Alice."));
        assertTrue(output.contains("Debited 10000 BRL from Alice."));
        assertTrue(output.contains("Alice:\n  BTC: available 0, reserved 0\n  BRL: available 490000, reserved 0"));
        assertTrue(output.endsWith("Goodbye.\n"));
        assertFalse(output.contains("Error:"));
    }

    @Test
    void restingBuyPrintsResultAndPublicBook() {
        String output = run("""
                1
                Charlie
                2
                Charlie
                BRL
                400000
                5
                Charlie
                BUY
                400000
                1
                7
                0
                """);
        assertTrue(output.contains("Order result:\n  ID: 1\n  Side: BUY\n  Status: OPEN"));
        assertTrue(output.contains("  Remaining: 1 BTC"));
        assertTrue(output.contains("BTC/BRL Order Book\nBIDS:\n  #1  1 BTC @ 400000 BRL\nASKS:\n  empty"));
        assertFalse(output.contains("Error:"));
    }

    @Test
    void crossingBuyPrintsRestingPriceTradeAndBalances() {
        String output = run("""
                1
                Alice
                1
                Bob
                2
                Alice
                BRL
                500000
                2
                Bob
                BTC
                1
                5
                Bob
                SELL
                490000
                1
                5
                Alice
                BUY
                500000
                1
                4
                Alice
                4
                Bob
                0
                """);
        assertTrue(output.contains("  ID: 2\n  Side: BUY\n  Status: FILLED"));
        assertTrue(output.contains("Trade:\n  BUY order: 2\n  SELL order: 1\n  Price: 490000 BRL/BTC\n  Quantity: 1 BTC"));
        assertTrue(output.contains("Alice:\n  BTC: available 1, reserved 0\n  BRL: available 10000, reserved 0"));
        assertTrue(output.contains("Bob:\n  BTC: available 0, reserved 0\n  BRL: available 490000, reserved 0"));
        assertFalse(output.contains("Error:"));
    }

    @Test
    void cancellationPrintsEmptyBookAndReleasedBalance() {
        String output = run("""
                1
                Charlie
                2
                Charlie
                BRL
                400000
                5
                Charlie
                BUY
                400000
                1
                6
                Charlie
                1
                7
                4
                Charlie
                0
                """);
        assertTrue(output.contains("Order 1 cancelled."));
        assertTrue(output.contains("BTC/BRL Order Book\nBIDS:\n  empty\nASKS:\n  empty"));
        assertTrue(output.contains("Charlie:\n  BTC: available 0, reserved 0\n  BRL: available 400000, reserved 0"));
        assertFalse(output.contains("Error:"));
    }

    @Test
    void invalidMenuDecimalAndDomainRequestsDoNotPreventLaterCommands() {
        String output = run("""
                99
                1
                Alice
                2
                Alice
                BRL
                not-a-number
                2
                Alice
                BRL
                -1
                2
                Alice
                BRL
                10
                3
                Alice
                BRL
                11
                4
                Alice
                0
                """);
        assertTrue(output.contains("Error: Invalid menu option."));
        assertTrue(output.contains("Error: Invalid decimal:"));
        assertTrue(output.contains("Error: Amount must be positive"));
        assertTrue(output.contains("Error: Insufficient balance"));
        assertTrue(output.contains("Alice:\n  BTC: available 0, reserved 0\n  BRL: available 10, reserved 0"));
        assertTrue(output.endsWith("Goodbye.\n"));
    }

    @Test
    void invalidIdentifiersAssetsSidesAndCancellationRemainRecoverable() {
        String output = run("""
                1

                1
                Alice
                1
                Alice
                4
                nobody
                2
                Alice
                ETH
                5
                Alice
                invalid-side
                6
                Alice
                abc
                6
                Alice
                999
                7
                0
                """);
        assertTrue(output.contains("Error: Account ID must not be blank"));
        assertTrue(output.contains("Error: Account ID is already registered"));
        assertTrue(output.contains("Error: Unknown account: nobody"));
        assertTrue(output.contains("Error: Asset must be BTC or BRL"));
        assertTrue(output.contains("Error: Side must be BUY or SELL"));
        assertTrue(output.contains("Error: Order ID must be a whole number"));
        assertTrue(output.contains("Error: Unknown order"));
        assertTrue(output.contains("BTC/BRL Order Book\nBIDS:\n  empty\nASKS:\n  empty"));
        assertTrue(output.endsWith("Goodbye.\n"));
    }

    @Test
    void selfTradeResultShowsCancelledBuyAndUnchangedRestingSell() {
        String output = run("""
                1
                Alice
                2
                Alice
                BTC
                1
                2
                Alice
                BRL
                500000
                5
                Alice
                SELL
                490000
                1
                5
                Alice
                BUY
                500000
                1
                7
                0
                """);
        assertTrue(output.contains("Order result:\n  ID: 1\n  Side: SELL\n  Status: OPEN"));
        assertTrue(output.contains("Order result:\n  ID: 2\n  Side: BUY\n  Status: CANCELLED"));
        assertFalse(output.contains("Trade:"));
        assertTrue(output.contains("BTC/BRL Order Book\nBIDS:\n  empty\nASKS:\n  #1  1 BTC @ 490000 BRL"));
        assertFalse(output.contains("#2"));
        assertFalse(output.contains("Error:"));
        assertTrue(output.endsWith("Goodbye.\n"));
    }

    @Test
    void endOfInputDuringCommandExitsWithoutStackTrace() {
        String output = run("1\nAlice\n2\nAlice\nBRL\n");
        assertTrue(output.contains("Account 'Alice' created."));
        assertTrue(output.endsWith("Amount:\n"));
        assertFalse(output.contains("Exception"));
    }
}
