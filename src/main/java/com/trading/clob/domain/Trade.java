package com.trading.clob.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record Trade(long buyOrderId, long sellOrderId, Instrument instrument,
                    BigDecimal executionPrice, BigDecimal executedQuantity) {
    public Trade {
        if (buyOrderId <= 0 || sellOrderId <= 0) {
            throw new IllegalArgumentException("Order IDs must be positive");
        }
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(executionPrice, "executionPrice");
        Objects.requireNonNull(executedQuantity, "executedQuantity");
        if (executionPrice.signum() <= 0 || executedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Execution price and quantity must be positive");
        }
    }
}
