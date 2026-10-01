package com.trading.clob.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable balance value; account updates replace the value as a whole. */
public record Balance(BigDecimal available, BigDecimal reserved) {
    public Balance {
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(reserved, "reserved");
        if (available.signum() < 0 || reserved.signum() < 0) {
            throw new IllegalArgumentException("Balances must not be negative");
        }
    }
}
