# tickloop

> Real-time JVM toolkit. Tick-based scheduler with main-thread safety
> guarantees, async I/O offload, and per-tick event queues. Building blocks
> for low-latency real-time servers: game backends, trading engines, live
> ops platforms.

[![CI](https://github.com/lucasfrederico/tickloop/actions/workflows/ci.yml/badge.svg)](https://github.com/lucasfrederico/tickloop/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)

## Why this exists

Many real-time servers follow the same pattern and reinvent the same
primitives every time:

1. One thread owns the state of truth (the "main thread" / "game thread").
2. That thread runs a tight loop at fixed rate, e.g. 20 Hz = 50 ms per tick.
3. Each tick: drain incoming events, run business logic, emit outgoing events.
4. Async I/O (DB, network, files) runs off the main thread; results funnel back.
5. Slow ticks are bugs — they ripple latency to every connected user.

Minecraft does it. Factorio does it. MMORPGs do it. High-frequency trading
engines do it. Live event platforms do it. Each codebase has its own ad-hoc
version of the same scheduler, the same "drain queue at start of tick"
pattern, the same "run this back on the main thread" callback.

`tickloop` is that pattern extracted into a small library you can drop into
a Java app and stop reinventing.

## Status

**v0.1.0.** Core API stable. JMH benchmarks and Spring Boot starter on the
v0.2.0 roadmap.

## Quickstart

Add the dependency (after v0.1.0 is published to Maven Central):

```xml
<dependency>
    <groupId>io.lucasfrederico</groupId>
    <artifactId>tickloop-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

Hello loop:

```java
import io.lucasfrederico.tickloop.TickLoop;
import java.time.Duration;

TickLoop loop = TickLoop.builder()
        .tickPeriod(Duration.ofMillis(50))    // 20 Hz
        .onTick(tick -> {
            // Runs on the loop thread, every 50 ms.
            // Mutate game/business state here freely.
        })
        .onSlowTick(stats -> System.out.println(
                "slow tick " + stats.tickNumber() + ": " + stats.durationMs() + " ms"))
        .build();

loop.start();
// ... later ...
loop.stop();
```

## Core concepts

### TickLoop — the central scheduler

Single-threaded fixed-rate loop. The thread it owns is the only thread
allowed to read/write your business state.

- **Tick rate.** Default 50 ms (20 Hz). Configurable.
- **Drift correction.** Scheduling is absolute (`startNanos + N * tickPeriod`),
  not relative — a sequence of slow ticks does not permanently push the
  schedule late.
- **Slow tick recovery.** When a tick overruns its budget, the next tick
  runs immediately, no padding sleep.
- **Slow tick listener.** Optional callback fires after any tick whose
  user-work duration exceeds a configurable threshold.

### `runOnMain` — bring work to the loop thread

```java
// From any thread:
loop.runOnMain(() -> {
    // Safe to mutate state owned by the loop thread.
    player.position = new Vec3(x, y, z);
});
```

Drained at the start of each tick, before any TickQueue and before the
user `onTick` handler.

### `offload` — push work off the loop thread

```java
// During a tick, fetch from a database without blocking the loop:
loop.offload(() -> database.loadPlayerProfile(id))
    .thenOnMain(profile -> {
        // Back on the loop thread; safe to mutate state.
        player.profile = profile;
    });
```

The `OffloadResult` only exposes `thenOnMain` (with optional error
handler) — there is no `thenApply` on the async pool. The library's
single-threaded contract holds by construction.

### TickQueue — MPSC events with auto-drain

```java
TickQueue<NetworkEvent> incoming =
    loop.createQueue("network", event -> handleNetworkEvent(event));

// From a network handler thread (multi-producer):
incoming.offer(new ConnectEvent(socket));

// On every tick, drained automatically into handleNetworkEvent on the loop thread.
```

Drain order per tick: `runOnMain` queue → registered TickQueues (in
registration order) → user `onTick` handler.

### TickMetrics — built-in counters

```java
TickMetrics m = loop.metrics();
m.tickCount();           // total ticks
m.slowTickCount();       // count above slow-tick threshold
m.lastDurationNanos();   // most recent tick's user work
m.maxDurationNanos();    // running max
m.avgDurationNanos();    // running mean
m.lastJitterNanos();     // how late the most recent tick started
```

p50/p95/p99 histograms are v0.2.0.

## Demo: `examples/echo-game`

A 100-line TCP server that broadcasts the current tick to every connected
client every second, and rebroadcasts what clients send. Shows
`TickLoop` + `TickQueue` + `runOnMain` working together. See
[`examples/echo-game/README.md`](examples/echo-game/README.md).

```
[server] listening on port 8765 (connect with: nc localhost 8765)
[server] client 1 connected from /[0:0:0:0:0:0:0:1]:50667
[server] welcome client 1. tick rate: 1 Hz.
[server] tick=2 clients=1
[client 1] hello from smoke test
[server] tick=3 clients=1
```

## Numbers (from the stress tests)

Measured on Apple Silicon (M-series), JDK 21, single shard, no other load:

| Workload | Result |
|----------|--------|
| 100 Hz no-op loop, 5 seconds | 501 ticks (target 500), 0 slow ticks, avg jitter 1.6 ms |
| Single TickQueue, 4 producers × 100k events | 400,000 events delivered, ~7M events/sec sustained, 0 loss |

Run with `mvn -pl tickloop-core test`. The stress tests print their own
numbers to stdout.

## Why not...

| Tool | Reason `tickloop` exists alongside it |
|------|---------------------------------------|
| **Vert.x** | Reactive event-loop model; not deterministic fixed-rate ticking. `tickloop` guarantees every tick completes within a bounded budget and reports slow ticks. |
| **Akka / Pekko** | Actor model, distributed-first. Heavy. `tickloop` is for one process owning local state with strict timing. |
| **Disruptor (LMAX)** | High-throughput message bus, a different abstraction. `tickloop` could use Disruptor internally for queues; the value the toolkit adds is tick semantics on top. |
| **Project Loom (virtual threads)** | Different problem: virtual threads make blocking-style async cheap. `tickloop`'s main thread is non-blocking by design. (`offload` can be wired to use virtual threads in v0.2.0.) |
| **Game engine threads** (Spigot, Unreal, Unity) | All ship ad-hoc versions of this pattern, baked into their engine. `tickloop` extracts the pattern for reuse. |

## Roadmap

### v0.1.0 (this release)

- ✅ `TickLoop` with fixed-rate scheduling + drift correction + slow tick detection
- ✅ `runOnMain` + `offload` + `thenOnMain`
- ✅ `TickQueue` MPSC events with auto-drain
- ✅ `TickMetrics` counters
- ✅ 29 tests including property-based + stress
- ✅ MIT license, CI on Java 17 + 21, multi-OS

### v0.2.0 (planned)

- Spring Boot starter (`tickloop-spring-boot-starter`) — auto-configure
  from `application.yml`, expose `TickMetrics` via Actuator endpoint
- p50 / p95 / p99 latency histograms (vendored HDR-style buckets, still zero deps)
- Multiple tick rates running in parallel (e.g. physics 60 Hz + AI 5 Hz
  on separate loops with cross-loop messaging)
- Backpressure modes on `TickQueue` (drop-oldest, drop-newest, block, fail-fast)
- Virtual-thread option for the offload pool
- JMH benchmark module with results published per CI run

## License

MIT — see [LICENSE](LICENSE).
