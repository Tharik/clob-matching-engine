package com.trading.clob.domain;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class Account {
    private static final Balance ZERO_BALANCE = new Balance(BigDecimal.ZERO, BigDecimal.ZERO);
    private final String id;
    private final Map<Asset, Balance> balances = new HashMap<>();

    public Account(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("Account ID must not be blank");
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Returns an immutable snapshot; missing assets are absent from the map. */
    public Map<Asset, Balance> balances() {
        return Map.copyOf(balances);
    }

    /** Returns a zero balance when the asset has no recorded balance; does not create an entry. */
    public Balance balanceOf(Asset asset) {
        Objects.requireNonNull(asset, "asset");
        return balances.getOrDefault(asset, ZERO_BALANCE);
    }

    public void setBalance(Asset asset, Balance balance) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(balance, "balance");
        balances.put(asset, balance);
    }
}
