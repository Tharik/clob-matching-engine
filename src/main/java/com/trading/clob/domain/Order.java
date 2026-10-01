package com.trading.clob.domain;

import java.math.BigDecimal;
import java.util.Objects;

public final class Order {
    private final long id;
    private final String accountId;
    private final Instrument instrument;
    private final OrderSide side;
    private final BigDecimal limitPrice;
    private final BigDecimal originalQuantity;
    private final long sequence;
    private BigDecimal remainingQuantity;
    private OrderStatus status;

    public Order(long id, String accountId, Instrument instrument, OrderSide side,
                 BigDecimal limitPrice, BigDecimal originalQuantity, long sequence) {
        if (id <= 0 || sequence <= 0) {
            throw new IllegalArgumentException("Order ID and sequence must be positive");
        }
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(limitPrice, "limitPrice");
        Objects.requireNonNull(originalQuantity, "originalQuantity");
        if (accountId.isBlank()) throw new IllegalArgumentException("Account ID must not be blank");
        if (limitPrice.signum() <= 0 || originalQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Limit price and original quantity must be positive");
        }
        this.id = id;
        this.accountId = accountId;
        this.instrument = instrument;
        this.side = side;
        this.limitPrice = limitPrice;
        this.originalQuantity = originalQuantity;
        this.remainingQuantity = originalQuantity;
        this.sequence = sequence;
        this.status = OrderStatus.OPEN;
    }

    public void applyFill(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() <= 0 || quantity.compareTo(remainingQuantity) > 0) {
            throw new IllegalArgumentException("Fill quantity must be positive and not exceed remaining quantity");
        }
        requireActive();
        remainingQuantity = remainingQuantity.subtract(quantity);
        status = remainingQuantity.signum() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel() {
        requireActive();
        status = OrderStatus.CANCELLED;
    }

    private void requireActive() {
        if (status != OrderStatus.OPEN && status != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalStateException("Order must be active");
        }
    }

    public long id() { return id; }
    public String accountId() { return accountId; }
    public Instrument instrument() { return instrument; }
    public OrderSide side() { return side; }
    public BigDecimal limitPrice() { return limitPrice; }
    public BigDecimal originalQuantity() { return originalQuantity; }
    public BigDecimal remainingQuantity() { return remainingQuantity; }
    public long sequence() { return sequence; }
    public OrderStatus status() { return status; }
}
