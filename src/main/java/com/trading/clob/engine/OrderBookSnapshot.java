package com.trading.clob.engine;

import com.trading.clob.domain.Instrument;
import java.util.List;
import java.util.Objects;

/**
 * Immutable point-in-time view of active orders in execution-priority order.
 *
 * <p>Bids are highest-price first, asks lowest-price first, with FIFO within each
 * price level. Lists are defensively copied; ownership and mutable order references
 * are not exposed. Later engine mutations do not change a captured snapshot.</p>
 */
public record OrderBookSnapshot(Instrument instrument, List<BookOrderView> bids, List<BookOrderView> asks) {
    public OrderBookSnapshot {
        Objects.requireNonNull(instrument, "instrument");
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
