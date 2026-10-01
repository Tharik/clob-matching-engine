package com.trading.clob.engine;

import com.trading.clob.domain.OrderStatus;
import com.trading.clob.domain.Trade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Immutable snapshot of an accepted order at the end of placement. */
public record PlacementResult(long orderId, OrderStatus status,
                              BigDecimal remainingQuantity, List<Trade> trades) {
    public PlacementResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(remainingQuantity, "remainingQuantity");
        trades = List.copyOf(trades);
    }
}
