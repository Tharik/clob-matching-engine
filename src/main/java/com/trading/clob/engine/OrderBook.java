package com.trading.clob.engine;

import com.trading.clob.domain.Instrument;
import com.trading.clob.domain.Order;
import com.trading.clob.domain.OrderSide;
import com.trading.clob.domain.OrderStatus;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Single-instrument book; serialized insertion order defines time priority within each price level. */
public final class OrderBook {
    private final Instrument instrument;
    private final NavigableMap<BigDecimal, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<BigDecimal, Deque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> ordersById = new HashMap<>();

    public OrderBook(Instrument instrument) {
        this.instrument = Objects.requireNonNull(instrument, "instrument");
    }

    public Instrument instrument() {
        return instrument;
    }

    public void add(Order order) {
        Objects.requireNonNull(order, "order");
        if (!instrument.equals(order.instrument())) {
            throw new IllegalArgumentException("Order belongs to another instrument");
        }
        if ((order.status() != OrderStatus.OPEN && order.status() != OrderStatus.PARTIALLY_FILLED)
                || order.remainingQuantity().signum() <= 0) {
            throw new IllegalArgumentException("Only active orders with remaining quantity may be added");
        }
        if (ordersById.containsKey(order.id())) {
            throw new IllegalArgumentException("Order ID is already in the book");
        }
        Deque<Order> level = side(order.side()).computeIfAbsent(order.limitPrice(), key -> new ArrayDeque<>());
        level.addLast(order);
        ordersById.put(order.id(), order);
    }

    /** Returns the resting order, or null when the side is empty. No collection is exposed. */
    public Order bestBid() {
        return best(bids);
    }

    /** Returns the resting order, or null when the side is empty. No collection is exposed. */
    public Order bestAsk() {
        return best(asks);
    }

    /** Returns the stored order, or null when the ID is absent. */
    public Order orderById(long id) {
        return ordersById.get(id);
    }

    /** Removes book membership only; also supports removal after a fill or cancellation. */
    public Order remove(long id) {
        Order order = ordersById.remove(id);
        if (order == null) return null;
        NavigableMap<BigDecimal, Deque<Order>> levels = side(order.side());
        Deque<Order> level = levels.get(order.limitPrice());
        level.remove(order);
        if (level.isEmpty()) levels.remove(order.limitPrice());
        return order;
    }

    OrderBookSnapshot snapshot() {
        return new OrderBookSnapshot(instrument, snapshotSide(bids), snapshotSide(asks));
    }

    private static List<BookOrderView> snapshotSide(NavigableMap<BigDecimal, Deque<Order>> levels) {
        List<BookOrderView> views = new ArrayList<>();
        for (Deque<Order> level : levels.values()) {
            for (Order order : level) {
                if (order.status() == OrderStatus.OPEN || order.status() == OrderStatus.PARTIALLY_FILLED) {
                    views.add(new BookOrderView(order.id(), order.limitPrice(), order.remainingQuantity(), order.status()));
                }
            }
        }
        return views;
    }

    private NavigableMap<BigDecimal, Deque<Order>> side(OrderSide side) {
        return side == OrderSide.BUY ? bids : asks;
    }

    private static Order best(NavigableMap<BigDecimal, Deque<Order>> levels) {
        var entry = levels.firstEntry();
        return entry == null ? null : entry.getValue().getFirst();
    }
}
