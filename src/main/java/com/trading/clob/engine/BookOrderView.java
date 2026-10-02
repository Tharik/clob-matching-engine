package com.trading.clob.engine;

import com.trading.clob.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable public market state of an open or partially filled order at capture time.
 * Contains its price and current remainder, without account ownership or a mutable
 * order reference. Decimal representation is preserved.
 */
public record BookOrderView(long orderId, BigDecimal limitPrice,
                            BigDecimal remainingQuantity, OrderStatus status) {
    public BookOrderView {
        Objects.requireNonNull(limitPrice, "limitPrice");
        Objects.requireNonNull(remainingQuantity, "remainingQuantity");
        Objects.requireNonNull(status, "status");
        if (orderId <= 0 || limitPrice.signum() <= 0 || remainingQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Order ID, price and remaining quantity must be positive");
        }
        if (status != OrderStatus.OPEN && status != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalArgumentException("Book order view must be active");
        }
    }
}
