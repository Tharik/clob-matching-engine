package com.trading.clob.engine;

import com.trading.clob.domain.Instrument;
import java.util.List;
import java.util.Objects;

/** Immutable book contents in execution priority order at the time of the query. */
public record OrderBookSnapshot(Instrument instrument, List<BookOrderView> bids, List<BookOrderView> asks) {
    public OrderBookSnapshot {
        Objects.requireNonNull(instrument, "instrument");
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
