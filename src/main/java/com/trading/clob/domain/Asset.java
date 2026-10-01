package com.trading.clob.domain;

import java.util.Objects;

public record Asset(String code) {
    public Asset {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) throw new IllegalArgumentException("Asset code must not be blank");
    }
}
