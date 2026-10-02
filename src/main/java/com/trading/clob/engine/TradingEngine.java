package com.trading.clob.engine;

import com.trading.clob.domain.Account;
import com.trading.clob.domain.Asset;
import com.trading.clob.domain.Instrument;
import com.trading.clob.domain.Order;
import com.trading.clob.domain.OrderSide;
import com.trading.clob.domain.OrderStatus;
import com.trading.clob.domain.Trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory orchestrator for reservation, matching, settlement and cancellation.
 *
 * <p>All commands, queries and mutations of registered accounts must be serialized.
 * Accepted orders reserve funds before execution; each match is settled before
 * the next candidate is inspected. Callers must not alter reservations backing
 * active orders through retained account references.</p>
 */
public final class TradingEngine {
    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<Instrument, OrderBook> books = new HashMap<>();
    private final Map<Long, Order> orders = new HashMap<>();
    private final MatchingEngine matching = new MatchingEngine();
    private long nextOrderId = 1;
    private long nextSequence = 1;

    /**
     * Registers the supplied mutable account by ID, retaining the same instance.
     *
     * @throws NullPointerException if the account is null
     * @throws IllegalArgumentException if its ID is already registered
     */
    public void registerAccount(Account account) {
        Objects.requireNonNull(account, "account");
        if (accounts.putIfAbsent(account.id(), account) != null) {
            throw new IllegalArgumentException("Account ID is already registered");
        }
    }

    /**
     * Registers an instrument and creates its independent, initially empty order book.
     *
     * @throws NullPointerException if the instrument is null
     * @throws IllegalArgumentException if the instrument is already registered
     */
    public void registerInstrument(Instrument instrument) {
        Objects.requireNonNull(instrument, "instrument");
        if (books.containsKey(instrument)) throw new IllegalArgumentException("Instrument is already registered");
        books.put(instrument, new OrderBook(instrument));
    }

    /**
     * Places a limit order with an engine-generated ID and sequence.
     *
     * <p>Validation precedes acceptance and reservation. Funds are reserved before
     * execution; the order may match immediately, and only an active remainder
     * rests in the book. A crossing self-order cancels the incoming remainder and
     * releases its reservation without undoing previous settlements.</p>
     *
     * <p>A reservation failure at initial preflight has no side effects. If a
     * later candidate fails reservation validation, prior settlements remain
     * final and the incoming remainder is cancelled and released before rethrowing.</p>
     *
     * @return immutable snapshot of the placement status, remainder and executed trades
     * @throws NullPointerException if a required argument is null
     * @throws IllegalArgumentException if input is invalid, registration is missing,
     *         or available funds are insufficient
     * @throws IllegalStateException if a book or reservation invariant is violated
     * @throws ArithmeticException if an ID or sequence counter is exhausted
     */
    public PlacementResult placeOrder(String accountId, Instrument instrument, OrderSide side,
                                      BigDecimal limitPrice, BigDecimal quantity) {
        Objects.requireNonNull(accountId, "accountId");
        if (accountId.isBlank()) throw new IllegalArgumentException("Account ID must not be blank");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(limitPrice, "limitPrice");
        Objects.requireNonNull(quantity, "quantity");
        if (limitPrice.signum() <= 0 || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Price and quantity must be positive");
        }
        Account account = accounts.get(accountId);
        if (account == null) throw new IllegalArgumentException("Unknown account");
        OrderBook book = books.get(instrument);
        if (book == null) throw new IllegalArgumentException("Unregistered instrument");
        Asset reservationAsset = side == OrderSide.BUY ? instrument.quoteAsset() : instrument.baseAsset();
        BigDecimal reservation = side == OrderSide.BUY ? limitPrice.multiply(quantity) : quantity;
        if (account.balanceOf(reservationAsset).available().compareTo(reservation) < 0) {
            throw new IllegalArgumentException("Insufficient available balance");
        }
        // Detect counter exhaustion before any state mutation rather than wrapping into invalid IDs.
        long followingId = Math.incrementExact(nextOrderId);
        long followingSequence = Math.incrementExact(nextSequence);
        Order incoming = new Order(nextOrderId, accountId, instrument, side, limitPrice, quantity, nextSequence);
        // Preflight the first execution before acceptance so immediate invariant failures have no side effects.
        Order firstCandidate = matching.nextMatchCandidate(book, incoming);
        if (firstCandidate != null && !firstCandidate.accountId().equals(accountId)) {
            validateReservations(incoming, firstCandidate, reservation);
        }
        account.reserve(reservationAsset, reservation);
        nextOrderId = followingId;
        nextSequence = followingSequence;
        orders.put(incoming.id(), incoming);

        List<Trade> trades = null;
        while (incoming.remainingQuantity().signum() > 0) {
            Order resting = matching.nextMatchCandidate(book, incoming);
            if (resting == null) break;
            if (resting.accountId().equals(incoming.accountId())) {
                releaseIncomingRemainder(incoming);
                incoming.cancel();
                break;
            }
            try {
                validateReservations(incoming, resting, BigDecimal.ZERO);
            } catch (IllegalStateException failure) {
                releaseIncomingRemainder(incoming);
                incoming.cancel();
                throw failure;
            }
            Trade trade = matching.matchNext(book, incoming);
            settle(trade);
            if (trades == null) trades = new ArrayList<>();
            trades.add(trade);
        }
        if (incoming.status() == OrderStatus.OPEN || incoming.status() == OrderStatus.PARTIALLY_FILLED) {
            book.add(incoming);
        }
        return new PlacementResult(incoming.id(), incoming.status(), incoming.remainingQuantity(),
                trades == null ? List.of() : trades);
    }

    /**
     * Cancels an owning account's active order and releases only its remaining reservation.
     *
     * <p>The order leaves the book but remains globally registered as cancelled.
     * Remaining quantity is preserved. Previously completed trades and settlements remain unchanged.
     * Invalid requests and detected cancellation invariant failures do not mutate state.</p>
     *
     * @throws NullPointerException if the account ID is null
     * @throws IllegalArgumentException if the account or order is unknown, ownership
     *         differs, the account ID is blank, or the order is already filled or cancelled
     * @throws IllegalStateException if book membership or remaining reservation is inconsistent
     */
    public void cancelOrder(String accountId, long orderId) {
        Objects.requireNonNull(accountId, "accountId");
        if (accountId.isBlank()) throw new IllegalArgumentException("Account ID must not be blank");
        Account account = accounts.get(accountId);
        if (account == null) throw new IllegalArgumentException("Unknown account");
        Order order = orders.get(orderId);
        if (order == null) throw new IllegalArgumentException("Unknown order");
        if (!order.accountId().equals(accountId)) {
            throw new IllegalArgumentException("Order belongs to another account");
        }
        if (order.status() == OrderStatus.FILLED) throw new IllegalArgumentException("Order is FILLED");
        if (order.status() == OrderStatus.CANCELLED) throw new IllegalArgumentException("Order is already CANCELLED");
        OrderBook book = books.get(order.instrument());
        if (book == null || book.orderById(orderId) != order) {
            throw new IllegalStateException("Active order is missing from its OrderBook");
        }
        Asset asset = order.side() == OrderSide.BUY ? order.instrument().quoteAsset() : order.instrument().baseAsset();
        BigDecimal reservation = order.side() == OrderSide.BUY
                ? order.limitPrice().multiply(order.remainingQuantity()) : order.remainingQuantity();
        if (reservation.signum() <= 0 || account.balanceOf(asset).reserved().compareTo(reservation) < 0) {
            throw new IllegalStateException("Active order has insufficient remaining reservation");
        }
        book.remove(orderId);
        account.release(asset, reservation);
        order.cancel();
    }

    private void releaseIncomingRemainder(Order incoming) {
        boolean buy = incoming.side() == OrderSide.BUY;
        Asset asset = buy ? incoming.instrument().quoteAsset() : incoming.instrument().baseAsset();
        BigDecimal reservation = buy
                ? incoming.limitPrice().multiply(incoming.remainingQuantity()) : incoming.remainingQuantity();
        accounts.get(incoming.accountId()).release(asset, reservation);
    }

    /** Pending reservation is nonzero only during preflight, before incoming funds are reserved. */
    private void validateReservations(Order incoming, Order resting, BigDecimal pendingReservation) {
        Order buy = incoming.side() == OrderSide.BUY ? incoming : resting;
        Order sell = incoming.side() == OrderSide.SELL ? incoming : resting;
        BigDecimal buyReserved = accounts.get(buy.accountId()).balanceOf(buy.instrument().quoteAsset()).reserved();
        BigDecimal sellReserved = accounts.get(sell.accountId()).balanceOf(sell.instrument().baseAsset()).reserved();
        if (pendingReservation.signum() > 0) {
            if (buy == incoming) buyReserved = buyReserved.add(pendingReservation);
            else sellReserved = sellReserved.add(pendingReservation);
        }
        if (buyReserved.compareTo(buy.limitPrice().multiply(buy.remainingQuantity())) < 0) {
            throw new IllegalStateException("Insufficient BUY reservation for full remaining quantity");
        }
        if (sellReserved.compareTo(sell.remainingQuantity()) < 0) {
            throw new IllegalStateException("Insufficient SELL reservation for full remaining quantity");
        }
    }

    private void settle(Trade trade) {
        Order buy = orders.get(trade.buyOrderId());
        Order sell = orders.get(trade.sellOrderId());
        Account buyer = accounts.get(buy.accountId());
        Account seller = accounts.get(sell.accountId());
        Instrument instrument = trade.instrument();
        BigDecimal reservedCost = buy.limitPrice().multiply(trade.executedQuantity());
        BigDecimal actualCost = trade.executionPrice().multiply(trade.executedQuantity());
        BigDecimal refund = reservedCost.subtract(actualCost);
        buyer.consumeReserved(instrument.quoteAsset(), reservedCost);
        if (refund.signum() > 0) buyer.creditAvailable(instrument.quoteAsset(), refund);
        buyer.creditAvailable(instrument.baseAsset(), trade.executedQuantity());
        seller.consumeReserved(instrument.baseAsset(), trade.executedQuantity());
        seller.creditAvailable(instrument.quoteAsset(), actualCost);
    }

    /**
     * Captures active public market state in execution-priority order.
     *
     * <p>The immutable point-in-time result contains no account ownership or mutable
     * order references and does not change after subsequent engine commands.
     * This query must be serialized with commands.</p>
     *
     * @return snapshot of the registered instrument's bids and asks
     * @throws NullPointerException if the instrument is null
     * @throws IllegalArgumentException if the instrument is not registered
     */
    public OrderBookSnapshot orderBookSnapshot(Instrument instrument) {
        Objects.requireNonNull(instrument, "instrument");
        OrderBook book = books.get(instrument);
        if (book == null) throw new IllegalArgumentException("Unregistered instrument");
        return book.snapshot();
    }

    // Internal inspection for engine code; mutable orders/books are not part of the public placement API.
    Order orderById(long id) {
        return orders.get(id);
    }

    OrderBook bookFor(Instrument instrument) {
        return books.get(instrument);
    }
}
