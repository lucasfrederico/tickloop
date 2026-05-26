# Changelog

## v0.2.0 — 2026-05-26

Second release. Picks up everything in the v0.1.0 roadmap.

### Added

- **Virtual-thread offload pool** (Java 21+). Opt-in via
  `TickLoopBuilder.useVirtualThreadsForOffload(true)`. The loop thread
  itself stays platform-threaded — virtual threads are unsuitable for
  the deterministic-timing main loop.
- **Latency histograms** in `TickMetrics`: `p50DurationNanos`,
  `p95DurationNanos`, `p99DurationNanos` (and the same for jitter).
  Backed by a vendored fixed-bucket log-scale histogram — lock-free,
  zero allocation on the hot path, zero runtime deps. Precision is 2x
  per bucket; use HdrHistogram standalone if you need 3-digit precision.
- **Backpressure modes** on `TickQueue`. Use
  `TickLoop.createBoundedQueue(name, capacity, mode, consumer)` with
  one of: `DROP_OLDEST`, `DROP_NEWEST`, `BLOCK`, `FAIL_FAST`.
  `FAIL_FAST` throws `QueueFullException`. New `TickQueue.droppedCount()`
  for observability.
- **`TickGroup`** — bundle of named `TickLoop` instances at independent
  tick rates, started and stopped together. Typical use: physics 60 Hz +
  AI 5 Hz + persistence snapshot 0.1 Hz in one game backend. Cross-loop
  messaging reuses thread-safe `TickQueue` — no new primitive.
- **`tickloop-spring-boot-starter`** — Spring Boot 3 auto-configuration.
  Bind `tickloop.*` properties from `application.yml`, expose `TickLoop`
  and `TickMetrics` beans. User provides a `TickHandler` bean; the
  starter wires the rest. `auto-start=true` by default, graceful stop
  on context shutdown via `DisposableBean`.
- **`tickloop-bench`** — JMH benchmark module. Throughput benchmarks
  for `TickQueue.offer` (unbounded, bounded, with 1 and 4 producer
  threads); tick overhead observation via `TickMetrics`. Build with
  `mvn -pl tickloop-bench package`, run with
  `java -jar tickloop-bench/target/tickloop-bench.jar`.

### Changed

- **Java baseline bumped from 17 to 21.** Virtual threads are the
  headline feature of v0.2 and require Java 21. CI matrix now covers
  Java 21 + 23 on Linux + macOS.

### Tests

- 53 tests across modules (48 in `tickloop-core` including property +
  stress; 5 in `tickloop-spring-boot-starter`).

## v0.1.0 — 2026-05-26

Initial public release. See
[v0.1.0 release notes](https://github.com/lucasfrederico/tickloop/releases/tag/v0.1.0)
for the full feature set:

- `TickLoop` with fixed-rate scheduling, drift correction, slow tick detection
- `runOnMain` + `offload` + `thenOnMain` patterns
- `TickQueue` MPSC events with auto-drain
- `TickMetrics` counters
- `examples/echo-game` TCP demo
- 29 tests, CI on Java 17 + 21 × Linux + macOS, MIT, zero runtime deps
