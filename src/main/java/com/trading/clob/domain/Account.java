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

    public void creditAvailable(Asset asset, BigDecimal amount) {
        validateAmount(asset, amount);
        Balance balance = balanceOf(asset);
        balances.put(asset, new Balance(balance.available().add(amount), balance.reserved()));
    }

    public void debitAvailable(Asset asset, BigDecimal amount) {
        validateAmount(asset, amount);
        Balance balance = balanceOf(asset);
        requireSufficient(balance.available(), amount);
        balances.put(asset, new Balance(balance.available().subtract(amount), balance.reserved()));
    }

    public void reserve(Asset asset, BigDecimal amount) {
        validateAmount(asset, amount);
        Balance balance = balanceOf(asset);
        requireSufficient(balance.available(), amount);
        balances.put(asset, new Balance(balance.available().subtract(amount), balance.reserved().add(amount)));
    }

    public void release(Asset asset, BigDecimal amount) {
        validateAmount(asset, amount);
        Balance balance = balanceOf(asset);
        requireSufficient(balance.reserved(), amount);
        balances.put(asset, new Balance(balance.available().add(amount), balance.reserved().subtract(amount)));
    }

    public void consumeReserved(Asset asset, BigDecimal amount) {
        validateAmount(asset, amount);
        Balance balance = balanceOf(asset);
        requireSufficient(balance.reserved(), amount);
        balances.put(asset, new Balance(balance.available(), balance.reserved().subtract(amount)));
    }

    private static void validateAmount(Asset asset, BigDecimal amount) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
    }

    private static void requireSufficient(BigDecimal balance, BigDecimal amount) {
        if (balance.compareTo(amount) < 0) throw new IllegalArgumentException("Insufficient balance");
    }

}
