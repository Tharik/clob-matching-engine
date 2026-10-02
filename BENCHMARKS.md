# TradingEngine benchmarks

The optional `benchmark` Maven profile adds JMH 1.37 and `src/jmh/java` only
when selected. The normal `mvn clean test` build does not compile benchmark
sources or require JMH. Java 25 annotation processing is explicitly configured;
the profile packages the JMH runner and its generated metadata in an executable
JAR. Processing is scoped to main compilation. A separate, forked compilation
of generated harness sources before packaging ensures usable bytecode on the
validated Java 25 environment; normal test compilation remains unchanged. No production implementation is modified.

## Build and run

Use JDK 25 and Maven:

```sh
mvn clean test
mvn -Pbenchmark clean package
java -jar target/benchmarks.jar -l
```

Defaults are one thread, three one-second warmup iterations, five one-second
measurement iterations, two forks, average time in microseconds, and a fixed
512 MiB heap per fork. JMH command-line options override warmup, measurement,
forks and modes. Exactly one thread is required and checked by the fixtures.

Average time, throughput, and selected latency distributions:

```sh
java -jar target/benchmarks.jar -bm avgt -tu us -t 1
java -jar target/benchmarks.jar -bm thrpt -tu s -t 1
java -jar target/benchmarks.jar '.*immediateMatch' -bm sample -tu us -t 1
```

Sample Time lets JMH report the sampled distribution and percentiles; no
percentiles are calculated separately. To retain machine-readable results,
append `-rf json -rff results.json`. Record the JDK/JVM version, JVM arguments,
OS, CPU, power settings and the complete command alongside any later results.
Run on an otherwise idle machine and compare equivalent configurations.

## Scenarios and operation units

All methods are in `com.trading.clob.benchmark.TradingEngineBenchmark` and call
the real public API. BUY and SELL accounts are distinct.

| Benchmark | Parameters | One reported operation |
| --- | --- | --- |
| `restingPlacement` | None | One BUY placement into an empty book, including validation, reservation, registration and insertion; no trade |
| `immediateMatch` | None | One BUY placement executing one resting SELL, including matching, settlement, price improvement and removal |
| `deepBookMatch` | `depth=100,1000,10000` | One BUY placement executing one best ASK from that many distinct price levels |
| `sweep` | `orderCount=10,100,1000` | One BUY placement consuming all preloaded SELL orders across distinct prices, including every trade and settlement; **not one operation per trade** |
| `cancellationStress` | `queueSize=10,100,1000,10000` | One cancellation of the last BUY in a same-price FIFO queue, including remainder reservation release |

The cancellation fixture deliberately targets the queue tail: ID lookup is
average O(1), price-level lookup is O(log P), and arbitrary deque removal scans
O(K) orders within the level. The benchmark preserves that implementation.

## Fixture boundaries and interpretation

Each scenario uses `@State(Scope.Thread)`. `@Setup(Level.Trial)` prepares
parameter-dependent prices and funding amounts and verifies the thread count.
`@Setup(Level.Invocation)` creates a fresh registered, funded engine and loads
resting orders through `placeOrder` before the measured command. This restores
equivalent valid state for every invocation and bounds the accepted-order
registry; no invocation progressively drains or grows a previous book.

`@TearDown(Level.Invocation)` verifies the expected result outside the measured
region: trade count and price, balances/reservations and book membership as
appropriate. In particular, a sweep must produce exactly `orderCount` trades,
a deep-book match consumes only the best order, and cancellation removes the
tail while retaining its predecessors. Matching returns its `PlacementResult`
to JMH; cancellation returns the target ID. There is no measured I/O, random
data, explicit GC, or self-trading.

Invocation fixtures make operation boundaries clear but are a deliberate
measurement limitation. JMH warns that this fixture level adds timing overhead
and should be used cautiously for short operations ([official JMH example](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_07_FixtureLevelInvocation.java)).
Repeated engine construction, population and verification can affect allocation,
GC and cache state even though their direct time is excluded from the operation
measurement. Large parameters increase wall-clock runtime substantially.
These results characterize a command on freshly prepared state, not sustained
throughput of a long-lived engine; fixture allocation also prevents attributing
whole-iteration GC profiler results solely to the measured command. Do not use
small latency differences as optimization evidence without further investigation.

## Quick smoke validation

Run only small fixtures with short iterations to validate the harness:

```sh
java -jar target/benchmarks.jar '.*restingPlacement' -wi 1 -i 1 -f 1 -w 100ms -r 100ms -t 1 -foe true
java -jar target/benchmarks.jar '.*immediateMatch' -wi 1 -i 1 -f 1 -w 100ms -r 100ms -t 1 -foe true
java -jar target/benchmarks.jar '.*deepBookMatch' -p depth=100 -wi 1 -i 1 -f 1 -w 100ms -r 100ms -t 1 -foe true
java -jar target/benchmarks.jar '.*sweep' -p orderCount=10 -wi 1 -i 1 -f 1 -w 100ms -r 100ms -t 1 -foe true
java -jar target/benchmarks.jar '.*cancellationStress' -p queueSize=10 -wi 1 -i 1 -f 1 -w 100ms -r 100ms -t 1 -foe true
```

Smoke runs prove fixture validity and runnable packaging, not stable performance.
No performance conclusions or optimization recommendations are implied.

## Benchmark Environment

The observed run used:

- Apple M4 Pro CPU;
- 51539607552 bytes physical memory;
- macOS 26.6.2, build 25G83;
- Temurin OpenJDK 25.0.1+8 LTS (64-bit Server VM);
- JMH 1.37 with one benchmark thread;
- three warmup iterations and five measurement iterations, one second each;
- two forks, each with a fixed 512 MiB heap (`-Xms512m -Xmx512m`).

Results are specific to this machine, JVM and configuration.

## Observed Results

These are observations from one developer-machine JMH run, not production SLAs,
guarantees or exchange-level performance claims. Average time and throughput
were measured in separate modes, rather than deriving one from the other.

| Scenario | Parameter | Average time | Throughput |
| --- | ---: | ---: | ---: |
| Resting placement | — | 0.0491 us/op | 20.45 M ops/s |
| Immediate match | 1 trade | 0.1428 us/op | 6.99 M ops/s |
| Deep book match | 100 levels | 0.2044 us/op | 4.67 M ops/s |
| Deep book match | 1,000 levels | 0.3698 us/op | 2.64 M ops/s |
| Deep book match | 10,000 levels | 0.7884 us/op | 1.32 M ops/s |
| Sweep | 10 trades | 0.9852 us/sweep | 1.04 M sweeps/s |
| Sweep | 100 trades | 11.4810 us/sweep | 90.34 K sweeps/s |
| Sweep | 1,000 trades | 129.4317 us/sweep | 7.76 K sweeps/s |
| Cancellation | queue 10 | 0.0390 us/op | 25.43 M ops/s |
| Cancellation | queue 100 | 0.0583 us/op | 16.97 M ops/s |
| Cancellation | queue 1,000 | 0.2466 us/op | 3.60 M ops/s |
| Cancellation | queue 10,000 | 1.8184 us/op | 578.82 K ops/s |

One sweep operation is one complete aggressive placement consuming N resting
orders, including the matching loop and settlement of every generated trade;
it is not one individual trade.

Raw JMH JSON output, when present, is preserved without content changes in
[`benchmark-results/`](benchmark-results/):
[average time](benchmark-results/benchmark-avgt.json),
[throughput](benchmark-results/benchmark-throughput.json) and
[sample time](benchmark-results/benchmark-sample.json).

### Sample-time example

For `immediateMatch`, the mean sample was approximately **0.151 us/op**:

| Percentile | Sample time |
| --- | ---: |
| p50 | 0.125 us |
| p95 | 0.167 us |
| p99 | 0.250 us |
| p99.9 | 0.458 us |

Much larger rare outliers were also observed. They should not be attributed
directly to the matching algorithm: scheduling, JVM behavior, GC/allocation
effects, CPU/cache state and the `Level.Invocation` fixture strategy can
influence tail observations.

## Interpretation

- **Deep book:** increasing distinct price levels from 100 to 10,000 increased
  measured matching cost substantially less than proportionally. This is
  directionally consistent with the ordered-tree design, but does not prove
  asymptotic complexity. The 10,000-level result showed noticeably more variance,
  so small numerical comparisons at that depth should not be overinterpreted.
- **Sweep:** total time grows roughly with the number of resting orders consumed.
  Each operation includes the complete matching loop and settlement of all
  generated trades; these observations do not establish production per-trade
  latency.
- **Cancellation:** the fixture intentionally exposes arbitrary removal from a
  same-price FIFO. Order ID lookup is average O(1), price-level access is
  O(log P), and `ArrayDeque` arbitrary removal is O(K) for K orders at that
  price level. Degradation with queue size is consistent with this trade-off.
  A production-oriented design could investigate direct-node handles or an
  intrusive linked structure if cancellation latency became important. These
  observations alone do not warrant changing the current exercise implementation.
