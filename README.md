# CLOB Matching Engine

A simplified, in-memory Central Limit Order Book (CLOB) and matching engine implemented in Java 25 for a technical interview exercise. The mandatory core is complete: limit order placement, matching, cancellation, and account balance management.

The implementation favors correctness, deterministic execution, and code that is straightforward to explain. It uses the Java standard library, Maven, and JUnit 5.

## Features

- BUY and SELL limit orders with price-time priority and partial fills.
- Execution at the resting order's price, with BUY price-improvement refunds.
- Per-asset available/reserved balances and immediate settlement after each trade.
- Self-trade prevention through cancel-aggressor behavior.
- Ownership-checked cancellation of active orders.
- Multiple instruments with independent books and shared account balances.
- Engine-generated monotonic order IDs and sequence numbers, global within each engine instance.
- Defensive reservation checks and immutable placement results.
- Immutable order-book snapshots in execution-priority order.
- Deterministic executable demo, interactive terminal console, and comprehensive automated test coverage.

## Architecture

| Component | Responsibility |
| --- | --- |
| `TradingEngine` | Registers accounts/instruments and orchestrates placement, reservation, individual executions, settlement, and cancellation. Retains all accepted orders and exposes the public book snapshot query. |
| `OrderBook` | Maintains one instrument's bids, asks, FIFO price levels, and active-order lookup. Structural removal does not cancel an order. |
| `MatchingEngine` | Inspects the next crossing candidate internally and executes at most one trade through `matchNext()`. It does not settle balances or enforce self-trade policy. |
| `Account` / `Balance` | Manage balances by asset through explicit credit, debit, reserve, release, and reserved-consumption operations. `Balance` is immutable. |
| `Order` | Owns controlled fill/cancel transitions; identity, original quantity, limit price, and sequence remain unchanged. |
| `OrderBookSnapshot` / `BookOrderView` | Immutable book and order views in execution-priority order, without account ownership or mutable order references. |
| Value types | `Asset`, `Instrument`, `OrderSide`, `OrderStatus`, and immutable `Trade` describe the domain. `PlacementResult` captures placement status, remainder, and trades without exposing a mutable order. |

The engine has no transport or infrastructure dependency. Accounts and instruments must be registered explicitly; duplicate registrations are rejected.

## Market Rules and Assumptions

- Limit orders only, with best-price priority and FIFO at equal prices.
- Trades execute at the resting order's price; partial fills are supported.
- Active orders reserve funds for their remaining quantity; BUY price improvement is returned immediately.
- Cancellation affects only the unfilled remainder.
- Self-trades are prevented by cancelling the incoming remainder.

## Core Rules

### Price-time priority and execution

Bids are ordered by highest price first; asks by lowest price first. Numerically equal prices share one FIFO level, including values such as `500.0` and `500.00`.

An incoming BUY crosses when the best ask is at or below its limit. An incoming SELL crosses when the best bid is at or above its limit. Execution uses the resting order's limit price and the smaller remaining quantity.

A partially filled resting order keeps its queue position and original sequence. When no further crossing liquidity exists, an active incoming remainder rests in the book. Filled orders leave the book.

### Reservations and settlement

For an instrument `base/quote`, BUY spends quote and receives base; SELL delivers base and receives quote.

| Side | Reservation on acceptance |
| --- | --- |
| BUY | `limitPrice × originalQuantity` in quote asset |
| SELL | `originalQuantity` in base asset |

For every execution of quantity `Q`:

- The buyer consumes `buyLimitPrice × Q` from reserved quote, receives `(buyLimitPrice − executionPrice) × Q` as available quote when positive, and receives `Q` as available base.
- The seller consumes `Q` from reserved base and receives `executionPrice × Q` as available quote.

Under maintained reservation invariants, the remaining BUY reservation requirement is `buyLimitPrice × remainingQuantity`; the SELL requirement is `remainingQuantity`. Reservations move existing assets between available and reserved; they do not create assets. An absent asset balance reads as zero without inserting a map entry.

### Self-trade prevention

If the best crossing order belongs to the incoming account, matching stops. The resting order is untouched; the incoming remainder is cancelled and its reservation released. Previous executions remain settled. The engine does not skip the self-order, which would violate price-time priority.

### Cancellation and lifecycle

`cancelOrder(accountId, orderId)` accepts only an owning account's `OPEN` or `PARTIALLY_FILLED` order. It removes book membership, releases only the remaining reservation, and transitions to `CANCELLED`. Previously completed trades and settlements remain unchanged; original and remaining quantities are preserved.

Unknown orders, wrong ownership, `FILLED` orders, and already `CANCELLED` orders are rejected explicitly. Accepted orders stay in the global registry after becoming `FILLED` or `CANCELLED`. Invalid submissions do not consume IDs/sequences or mutate balances/books; there is no persisted `REJECTED` status.

## Optional Support Operations

In addition to the mandatory CLOB operations, all four optional support operations suggested by the exercise are implemented:

- Credit an asset: `Account.creditAvailable(asset, amount)`.
- Debit an asset: `Account.debitAvailable(asset, amount)`.
- Query account balance: `Account.balanceOf(asset)` for direct lookup, or `balances()` for a safe inspection snapshot.
- Query current order book: `TradingEngine.orderBookSnapshot(instrument)`.

The order-book query returns an immutable point-in-time snapshot ordered by execution priority. It exposes neither account ownership nor mutable `Order` objects. Snapshot creation traverses active book contents in O(number of active orders) on the query path.

## Correctness Properties

- Invalid or insufficient-balance submissions consume no order IDs/sequences and mutate neither books nor balances.
- Reservations are established before accepted orders execute; backing for both orders' full current remainder is validated before matching.
- Each successful match is settled before the next candidate is inspected.
- Partial fills preserve time priority, and BUY price improvement is released immediately.
- Cancellation releases only the active remainder reservation.
- Self-trade prevention cancels the aggressor remainder rather than skipping a higher-priority self-order.
- Active book snapshots exclude `FILLED`/`CANCELLED` orders and are immutable.
- Integration tests verify asset conservation and multi-instrument isolation.

## Data Structures

- **`TreeMap<BigDecimal, Deque<Order>>`:** price levels with reverse ordering for bids and natural ordering for asks. Numeric comparison groups equal prices without normalizing the order's stored price. Price-level operations and best-level access are O(log P), where P is the number of levels.
- **`ArrayDeque<Order>`:** FIFO insertion at the tail and consumption from the head, with amortized O(1) queue operations and no linked-list node per order.
- **`HashMap`:** average O(1) account/order lookup and instrument-to-book routing. Cancellation finds the order directly, then removes it from its own price queue: O(log P + K) worst-case work for K orders at that level. A filled head order does not require scanning the level.

Matching and settlement use direct loops. There are no Streams, logging, I/O, database calls, or messaging in the matching loop.

## Assumptions and Trade-offs

- **Serialized processing:** all engine access and mutations of registered accounts must be serialized. Plain `long` counters need no locks, `AtomicLong`, or synchronization. This supports deterministic ordering for the exercise; it is not a complete production concurrency architecture.
- **Logical per-trade atomicity:** inspect candidate → self-trade check → reservation check → one match → immediate settlement → next candidate. Accepted orders reserve before execution. Previous successful trades are not rolled back if a later reservation invariant fails.
- **Defensive checks:** both orders must have aggregate reserved funds sufficient for their entire current remainder before execution. The incoming pending reservation is accounted for during initial preflight. A first-counterparty reservation failure occurs before acceptance, with no side effects. A later reservation failure cancels/releases the accepted incoming remainder, preserves prior settlements and the resting candidate, and rethrows `IllegalStateException`. There is no transaction manager or general rollback guarantee.
- **Trusted account ownership:** registered `Account` objects are mutable references. Callers must not externally consume/release reservations backing active orders. Checks detect obvious underfunding, but aggregate reserves can mask deficits across orders; there is no per-order reservation ledger or ownership isolation.
- **Exact numeric arithmetic:** prices, quantities, and balances use `BigDecimal`; no `double`/`float`, arbitrary rounding, or fixed scale. Trading comparisons use `compareTo()` where scale should not matter. Stored representation is preserved; record equality remains scale-sensitive. No tick-size or lot-size rules are imposed.
- **MVP boundaries:** no fees, authentication layer, network/API transport, or persistence. Account IDs are caller-supplied identifiers, not authenticated identities. Asset codes use exact, case-sensitive equality.
- **In-memory lifetime:** state is lost on restart, and accepted order history remains in memory for the life of the engine.
- **Latency awareness:** standard-library structures avoid unnecessary layers, but `BigDecimal` arithmetic and immutable `Balance` replacement allocate objects. Correctness precedes micro-optimization. No ultra-low-latency claim is made without benchmarks.

## Requirements

- JDK 25
- Maven

## Build and Test

From the repository root:

```sh
mvn clean test
```

The test suite covers domain invariants, book ordering, matching, settlement, self-trade prevention, cancellation, reservation hardening, multiple instruments, asset conservation, snapshot semantics, demo output, and scripted console interaction.

## Run Demo

Two entry points are available: `App.java` for a deterministic demonstration and `ConsoleApp.java` for interactive manual operation.

```sh
mvn -q -DskipTests package
java -cp target/classes com.trading.clob.App
```

The demo prints a resting SELL matched by an incoming BUY, the resulting execution and balances, then a separate BUY reservation followed by cancellation, with order-book snapshots before and after cancellation. It uses only the engine's public API and requires no input.

## Interactive Console

`ConsoleApp` is a standard-library terminal adapter over the existing engine, initialized with the BTC/BRL market.

```sh
mvn -q -DskipTests package
java -cp target/classes com.trading.clob.ConsoleApp
```

The menu supports creating accounts, crediting/debiting BTC or BRL available balance, querying balances, placing limit orders, cancelling orders, and viewing the BTC/BRL order book. Normal invalid input prints an error and returns to the menu; choose `0` to exit.

The console handles input and presentation only. Matching, settlement, reservation, and cancellation rules remain in `TradingEngine` and the domain/engine classes.

## Example Scenario

Bob has 1 BTC and places **SELL 1 BTC @ 490000 BRL**. Alice has 500000 BRL and submits **BUY 1 BTC @ 500000 BRL**.

The trade executes at **490000 BRL**, Bob's resting price. Alice receives **1 BTC** and a **10000 BRL** price-improvement refund. Bob receives **490000 BRL**. Both orders fill and their reservations are fully consumed or released.

## Project Structure

```text
src/main/java/com/trading/clob/
├── App.java                 Deterministic demonstration
├── ConsoleApp.java          Interactive manual operation
├── domain/                  Assets, instruments, accounts, balances, orders, trades
└── engine/                  OrderBook, MatchingEngine, TradingEngine, PlacementResult

src/test/java/com/trading/clob/
├── AppTest.java             Demo output check
├── ConsoleAppTest.java      Scripted console interaction tests
├── domain/                  Domain and account-operation tests
└── engine/                  Book, matching, placement, cancellation, integration tests
```

## Possible Production Extensions

These are outside the completed MVP:

- Persistence through a journal and snapshots, with durability coordinated around execution rather than database I/O inside the matching loop.
- Transport adapters such as FIX or REST, plus authentication at the application boundary.
- Asynchronous downstream event publication outside the matching hot path.
- Profiling and benchmark-driven allocation/numeric optimization, potentially including fixed-point representation with explicit precision rules.
- Richer market rules such as tick size, lot size, and fees.
