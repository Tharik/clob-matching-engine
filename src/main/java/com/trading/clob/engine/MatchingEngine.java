package com.trading.clob.engine;

import com.trading.clob.domain.Order;
import com.trading.clob.domain.OrderSide;
import com.trading.clob.domain.OrderStatus;
import com.trading.clob.domain.Trade;

import java.math.BigDecimal;
import java.util.Objects;

/** Pure matching over a book and incoming order; callers must serialize access. */
public final class MatchingEngine {
    /** Executes at most one trade, or returns null if no price crosses. */
    public Trade matchNext(OrderBook book, Order incoming) {
        Order resting = nextMatchCandidate(book, incoming);
        if (resting == null) return null;
        boolean buy = incoming.side() == OrderSide.BUY;
        BigDecimal quantity = incoming.remainingQuantity().min(resting.remainingQuantity());
        Trade trade = new Trade(buy ? incoming.id() : resting.id(),
                buy ? resting.id() : incoming.id(), book.instrument(), resting.limitPrice(), quantity);
        incoming.applyFill(quantity);
        resting.applyFill(quantity);
        if (resting.status() == OrderStatus.FILLED) book.remove(resting.id());
        return trade;
    }

    private static void validate(OrderBook book, Order incoming) {
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(incoming, "incoming");
        if (!book.instrument().equals(incoming.instrument())) {
            throw new IllegalArgumentException("Incoming order belongs to another instrument");
        }
        if ((incoming.status() != OrderStatus.OPEN && incoming.status() != OrderStatus.PARTIALLY_FILLED)
                || incoming.remainingQuantity().signum() <= 0) {
            throw new IllegalArgumentException("Incoming order must be active");
        }
        if (book.orderById(incoming.id()) != null) {
            throw new IllegalArgumentException("Incoming order ID is already in the book");
        }
    }

    Order nextMatchCandidate(OrderBook book, Order incoming) {
        validate(book, incoming);
        boolean buy = incoming.side() == OrderSide.BUY;
        Order resting = buy ? book.bestAsk() : book.bestBid();
        if (resting == null) return null;
        int priceComparison = resting.limitPrice().compareTo(incoming.limitPrice());
        if (buy ? priceComparison > 0 : priceComparison < 0) return null;
        // Stored orders must remain active until removed by the serialized caller.
        if ((resting.status() != OrderStatus.OPEN && resting.status() != OrderStatus.PARTIALLY_FILLED)
                || resting.remainingQuantity().signum() <= 0) {
            throw new IllegalStateException("Best resting order must be active");
        }
        return resting;
    }
}
