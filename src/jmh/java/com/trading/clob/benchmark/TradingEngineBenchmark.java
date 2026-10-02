package com.trading.clob.benchmark;

import com.trading.clob.domain.Account;
import com.trading.clob.domain.Asset;
import com.trading.clob.domain.Instrument;
import com.trading.clob.domain.OrderSide;
import com.trading.clob.domain.OrderStatus;
import com.trading.clob.engine.PlacementResult;
import com.trading.clob.engine.TradingEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/** Each invocation measures one public command against a freshly prepared engine. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Threads(1)
public class TradingEngineBenchmark {
    private static final Asset BTC = new Asset("BTC");
    private static final Asset BRL = new Asset("BRL");
    private static final Instrument MARKET = new Instrument(BTC, BRL);
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal BUY_LIMIT = BigDecimal.valueOf(500);
    private static final BigDecimal ASK_PRICE = BigDecimal.valueOf(490);

    private static Fixture fixture(BigDecimal quote, BigDecimal base) {
        TradingEngine engine = new TradingEngine();
        Account buyer = new Account("buyer");
        Account seller = new Account("seller");
        if (quote.signum() > 0) buyer.creditAvailable(BRL, quote);
        if (base.signum() > 0) seller.creditAvailable(BTC, base);
        engine.registerInstrument(MARKET);
        engine.registerAccount(buyer);
        engine.registerAccount(seller);
        return new Fixture(engine, buyer, seller);
    }

    private record Fixture(TradingEngine engine, Account buyer, Account seller) { }

    private static void singleThread(BenchmarkParams params) {
        if (params.getThreads() != 1) throw new IllegalArgumentException("Use exactly one benchmark thread");
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("Benchmark fixture/result invariant failed");
    }

    private static void equal(BigDecimal actual, BigDecimal expected) {
        require(actual.compareTo(expected) == 0);
    }

    @State(Scope.Thread)
    public static class RestingState {
        private Fixture fixture;
        private PlacementResult result;

        @Setup(Level.Trial)
        public void configure(BenchmarkParams params) { singleThread(params); }

        @Setup(Level.Invocation)
        public void prepare() { fixture = fixture(BUY_LIMIT, BigDecimal.ZERO); }

        @TearDown(Level.Invocation)
        public void verify() {
            require(result.status() == OrderStatus.OPEN && result.trades().isEmpty());
            equal(result.remainingQuantity(), ONE);
            equal(fixture.buyer.balanceOf(BRL).reserved(), BUY_LIMIT);
            equal(fixture.buyer.balanceOf(BRL).available(), BigDecimal.ZERO);
            require(fixture.engine.orderBookSnapshot(MARKET).bids().size() == 1);
        }
    }

    @Benchmark
    public PlacementResult restingPlacement(RestingState state) {
        return state.result = state.fixture.engine.placeOrder("buyer", MARKET, OrderSide.BUY, BUY_LIMIT, ONE);
    }

    @State(Scope.Thread)
    public static class ImmediateState {
        private Fixture fixture;
        private PlacementResult result;
        private long restingId;

        @Setup(Level.Trial)
        public void configure(BenchmarkParams params) { singleThread(params); }

        @Setup(Level.Invocation)
        public void prepare() {
            fixture = fixture(BUY_LIMIT, ONE);
            restingId = fixture.engine.placeOrder("seller", MARKET, OrderSide.SELL, ASK_PRICE, ONE).orderId();
        }

        @TearDown(Level.Invocation)
        public void verify() {
            require(result.status() == OrderStatus.FILLED && result.trades().size() == 1);
            require(result.trades().getFirst().sellOrderId() == restingId);
            equal(result.trades().getFirst().executionPrice(), ASK_PRICE);
            equal(result.trades().getFirst().executedQuantity(), ONE);
            equal(fixture.buyer.balanceOf(BRL).available(), BUY_LIMIT.subtract(ASK_PRICE));
            equal(fixture.buyer.balanceOf(BRL).reserved(), BigDecimal.ZERO);
            equal(fixture.buyer.balanceOf(BTC).available(), ONE);
            equal(fixture.seller.balanceOf(BRL).available(), ASK_PRICE);
            require(fixture.engine.orderBookSnapshot(MARKET).asks().isEmpty());
        }
    }

    @Benchmark
    public PlacementResult immediateMatch(ImmediateState state) {
        return state.result = state.fixture.engine.placeOrder("buyer", MARKET, OrderSide.BUY, BUY_LIMIT, ONE);
    }

    @State(Scope.Thread)
    public static class DeepState {
        @Param({"100", "1000", "10000"})
        public int depth;
        private Fixture fixture;
        private PlacementResult result;
        private BigDecimal[] prices;
        private BigDecimal quantity;
        private long bestId;

        @Setup(Level.Trial)
        public void configure(BenchmarkParams params) {
            singleThread(params);
            require(depth >= 2);
            quantity = BigDecimal.valueOf(depth);
            prices = prices(depth);
        }

        @Setup(Level.Invocation)
        public void prepare() {
            fixture = fixture(BUY_LIMIT, quantity);
            for (int i = 0; i < depth; i++) {
                PlacementResult resting = fixture.engine.placeOrder("seller", MARKET, OrderSide.SELL, prices[i], ONE);
                if (i == 0) bestId = resting.orderId();
            }
        }

        @TearDown(Level.Invocation)
        public void verify() {
            require(result.status() == OrderStatus.FILLED && result.trades().size() == 1);
            require(result.trades().getFirst().sellOrderId() == bestId);
            equal(result.trades().getFirst().executionPrice(), prices[0]);
            equal(result.trades().getFirst().executedQuantity(), ONE);
            equal(fixture.seller.balanceOf(BTC).reserved(), quantity.subtract(ONE));
            equal(fixture.buyer.balanceOf(BRL).reserved(), BigDecimal.ZERO);
            var snapshot = fixture.engine.orderBookSnapshot(MARKET);
            require(snapshot.asks().size() == depth - 1);
            equal(snapshot.asks().getFirst().limitPrice(), prices[1]);
        }
    }

    @Benchmark
    public PlacementResult deepBookMatch(DeepState state) {
        return state.result = state.fixture.engine.placeOrder("buyer", MARKET, OrderSide.BUY, BUY_LIMIT, ONE);
    }

    private static BigDecimal[] prices(int count) {
        BigDecimal[] prices = new BigDecimal[count];
        for (int i = 0; i < count; i++) prices[i] = BigDecimal.valueOf(490L + i);
        return prices;
    }

    @State(Scope.Thread)
    public static class SweepState {
        @Param({"10", "100", "1000"})
        public int orderCount;
        private Fixture fixture;
        private PlacementResult result;
        private BigDecimal[] prices;
        private BigDecimal quantity;
        private BigDecimal limit;
        private BigDecimal funding;
        private BigDecimal executionCost;

        @Setup(Level.Trial)
        public void configure(BenchmarkParams params) {
            singleThread(params);
            require(orderCount > 0);
            prices = prices(orderCount);
            quantity = BigDecimal.valueOf(orderCount);
            limit = prices[orderCount - 1];
            funding = limit.multiply(quantity);
            executionCost = BigDecimal.ZERO;
            for (BigDecimal price : prices) executionCost = executionCost.add(price);
        }

        @Setup(Level.Invocation)
        public void prepare() {
            fixture = fixture(funding, quantity);
            for (BigDecimal price : prices) fixture.engine.placeOrder("seller", MARKET, OrderSide.SELL, price, ONE);
        }

        @TearDown(Level.Invocation)
        public void verify() {
            require(result.status() == OrderStatus.FILLED && result.trades().size() == orderCount);
            for (int i = 0; i < orderCount; i++) {
                var trade = result.trades().get(i);
                equal(trade.executionPrice(), prices[i]);
                equal(trade.executedQuantity(), ONE);
                require(trade.sellOrderId() == i + 1L);
            }
            equal(fixture.buyer.balanceOf(BTC).available(), quantity);
            equal(fixture.buyer.balanceOf(BRL).available(), funding.subtract(executionCost));
            equal(fixture.seller.balanceOf(BRL).available(), executionCost);
            equal(fixture.buyer.balanceOf(BRL).reserved(), BigDecimal.ZERO);
            equal(fixture.seller.balanceOf(BTC).reserved(), BigDecimal.ZERO);
            require(fixture.engine.orderBookSnapshot(MARKET).asks().isEmpty());
        }
    }

    /** One operation is one complete sweep, not one trade. */
    @Benchmark
    public PlacementResult sweep(SweepState state) {
        return state.result = state.fixture.engine.placeOrder("buyer", MARKET, OrderSide.BUY, state.limit, state.quantity);
    }

    @State(Scope.Thread)
    public static class CancellationState {
        @Param({"10", "100", "1000", "10000"})
        public int queueSize;
        private Fixture fixture;
        private BigDecimal funding;
        private long targetId;

        @Setup(Level.Trial)
        public void configure(BenchmarkParams params) {
            singleThread(params);
            require(queueSize > 1);
            funding = BUY_LIMIT.multiply(BigDecimal.valueOf(queueSize));
        }

        @Setup(Level.Invocation)
        public void prepare() {
            fixture = fixture(funding, BigDecimal.ZERO);
            for (int i = 0; i < queueSize; i++) {
                targetId = fixture.engine.placeOrder("buyer", MARKET, OrderSide.BUY, BUY_LIMIT, ONE).orderId();
            }
        }

        @TearDown(Level.Invocation)
        public void verify() {
            equal(fixture.buyer.balanceOf(BRL).available(), BUY_LIMIT);
            equal(fixture.buyer.balanceOf(BRL).reserved(), funding.subtract(BUY_LIMIT));
            var bids = fixture.engine.orderBookSnapshot(MARKET).bids();
            require(bids.size() == queueSize - 1);
            require(bids.getLast().orderId() == targetId - 1);
        }
    }

    @Benchmark
    public long cancellationStress(CancellationState state) {
        state.fixture.engine.cancelOrder("buyer", state.targetId);
        return state.targetId;
    }
}
