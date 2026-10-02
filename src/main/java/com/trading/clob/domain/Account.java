package com.trading.clob.domain;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable per-asset account state with immutable available/reserved balance values.
 *
 * <p>Available amounts can be spent; reserved amounts are committed until released
 * or consumed. Missing assets read as zero without creating entries. Mutations
 * validate before replacing a balance and require serialized access.</p>
 */
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

    /**
     * Captures the recorded asset balances in an immutable inspection snapshot.
     * Missing assets are absent from the map; later mutations do not change this result.
     * Use {@link #balanceOf(Asset)} for direct lookup without copying the map.
     *
     * @return immutable snapshot of explicitly recorded balances
     */
    public Map<Asset, Balance> balances() {
        return Map.copyOf(balances);
    }

    /**
     * Reads an immutable balance directly, returning zero available and reserved if absent.
     * Querying a missing asset does not insert an entry.
     *
     * @return the recorded balance or an immutable zero balance
     * @throws NullPointerException if the asset is null
     */
    public Balance balanceOf(Asset asset) {
        Objects.requireNonNull(asset, "asset");
        return balances.getOrDefault(asset, ZERO_BALANCE);
    }

    /**
     * Credits a strictly positive amount to available balance, leaving reserved unchanged.
     * Validation failure leaves account state unchanged.
     *
     * @throws NullPointerException if the asset or amount is null
     * @throws IllegalArgumentException if the amount is not positive
     */
    public void creditAvailable(Asset asset, BigDecimal amount) {
        validateAmount(asset, amount);
        Balance balance = balanceOf(asset);
        balances.put(asset, new Balance(balance.available().add(amount), balance.reserved()));
    }

    /**
     * Debits a strictly positive amount from available balance, leaving reserved unchanged.
     * Validation failure leaves account state unchanged.
     *
     * @throws NullPointerException if the asset or amount is null
     * @throws IllegalArgumentException if the amount is not positive or available balance is insufficient
     */
    public void debitAvailable(Asset asset, BigDecimal amount) {
        validateAmount(asset, amount);
        Balance balance = balanceOf(asset);
        requireSufficient(balance.available(), amount);
        balances.put(asset, new Balance(balance.available().subtract(amount), balance.reserved()));
    }

    /**
     * Moves a strictly positive amount from available to reserved balance.
     * Validation failure leaves account state unchanged.
     *
     * @throws NullPointerException if the asset or amount is null
     * @throws IllegalArgumentException if the amount is not positive or available balance is insufficient
     */
    public void reserve(Asset asset, BigDecimal amount) {
        validateAmount(asset, amount);
        Balance balance = balanceOf(asset);
        requireSufficient(balance.available(), amount);
        balances.put(asset, new Balance(balance.available().subtract(amount), balance.reserved().add(amount)));
    }

    /**
     * Moves a strictly positive amount from reserved back to available balance.
     * Validation failure leaves account state unchanged.
     *
     * @throws NullPointerException if the asset or amount is null
     * @throws IllegalArgumentException if the amount is not positive or reserved balance is insufficient
     */
    public void release(Asset asset, BigDecimal amount) {
        validateAmount(asset, amount);
        Balance balance = balanceOf(asset);
        requireSufficient(balance.reserved(), amount);
        balances.put(asset, new Balance(balance.available().add(amount), balance.reserved().subtract(amount)));
    }

    /**
     * Consumes a strictly positive reserved amount without increasing available balance.
     * Validation failure leaves account state unchanged.
     *
     * @throws NullPointerException if the asset or amount is null
     * @throws IllegalArgumentException if the amount is not positive or reserved balance is insufficient
     */
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
