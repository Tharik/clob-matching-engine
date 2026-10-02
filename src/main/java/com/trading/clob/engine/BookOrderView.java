package com.trading.clob.engine;

import com.trading.clob.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.Objects;

/** Immutable public market view of an active order, without account ownership information. */
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
