package com.trading.clob.domain;

import java.util.Objects;

public record Instrument(Asset baseAsset, Asset quoteAsset) {
    public Instrument {
        Objects.requireNonNull(baseAsset, "baseAsset");
        Objects.requireNonNull(quoteAsset, "quoteAsset");
        if (baseAsset.equals(quoteAsset)) {
            throw new IllegalArgumentException("Base and quote assets must differ");
        }
    }
}
