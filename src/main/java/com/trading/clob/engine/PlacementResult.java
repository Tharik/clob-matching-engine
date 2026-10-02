package com.trading.clob.engine;

import com.trading.clob.domain.OrderStatus;
import com.trading.clob.domain.Trade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of an accepted order's status, remainder and trades at placement completion.
 *
 * <p>The trade list is defensively copied and no mutable order is exposed. Later
 * fills or cancellation do not update this result; it is not a live order-status view.</p>
 */
public record PlacementResult(long orderId, OrderStatus status,
                              BigDecimal remainingQuantity, List<Trade> trades) {
    public PlacementResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(remainingQuantity, "remainingQuantity");
        trades = List.copyOf(trades);
    }
}
