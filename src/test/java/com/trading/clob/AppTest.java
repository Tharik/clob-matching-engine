package com.trading.clob;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTest {
    @Test
    void demoPrintsMatchingAndCancellationResults() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            App.main(new String[0]);
        } finally {
            System.setOut(original);
        }
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("--- Scenario 1: Matching ---"));
        assertTrue(text.contains("Execution price: 490000 BRL/BTC"));
        assertTrue(text.contains("Alice's BUY: order 2, status FILLED, remaining quantity 0 BTC"));
        assertTrue(text.contains("--- Scenario 2: Cancellation ---"));
        assertTrue(text.contains("Charlie's BUY: order 3, status OPEN, remaining quantity 1 BTC"));
        String bookBeforeCancellation = String.join(System.lineSeparator(),
                "Order book:", "  BIDS:", "    order 3: 1 BTC @ 400000 BRL", "  ASKS:", "    empty");
        String bookAfterCancellation = String.join(System.lineSeparator(),
                "Order book:", "  BIDS:", "    empty", "  ASKS:", "    empty");
        assertTrue(text.contains(bookBeforeCancellation));
        assertTrue(text.indexOf(bookBeforeCancellation) < text.indexOf("Charlie cancels BUY order 3"));
        assertTrue(text.contains(bookAfterCancellation));
        assertTrue(text.indexOf(bookAfterCancellation) > text.indexOf("After cancellation:"));
        assertTrue(text.contains("Charlie cancels BUY order 3"));
        assertTrue(text.contains("After cancellation:" + System.lineSeparator()
                + "Charlie:" + System.lineSeparator()
                + "  BTC: available 0, reserved 0" + System.lineSeparator()
                + "  BRL: available 400000, reserved 0"));
    }
}
