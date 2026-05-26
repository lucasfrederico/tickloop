package io.lucasfrederico.tickloop;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregate timing statistics for a {@link TickLoop}.
 *
 * <p>All counters are safe to read from any thread. Internally:
 * <ul>
 *   <li>{@link LongAdder} for high-frequency totals (tick count) — avoids
 *       single-cell contention if the loop's tick rate is high.</li>
 *   <li>{@link AtomicLong} for "last sample" gauges — single-writer (loop
 *       thread) so volatile semantics are enough.</li>
 *   <li>{@link LongAccumulator} for max-tracking — lock-free max.</li>
 * </ul>
 *
 * <p>v0.1.0 exposes counts + last/max gauges + running average. A proper
 * percentile histogram (p50/p95/p99) is on the v0.2.0 roadmap.
 */
public final class TickMetrics {

    private final LongAdder tickCount = new LongAdder();
    private final LongAdder slowTickCount = new LongAdder();

    private final AtomicLong lastDurationNanos = new AtomicLong();
    private final AtomicLong lastJitterNanos = new AtomicLong();

    private final LongAccumulator maxDurationNanos = new LongAccumulator(Math::max, 0L);
    private final LongAccumulator maxJitterNanos = new LongAccumulator(Math::max, 0L);

    // Running mean via Welford-ish: keep sum + count, read avg = sum / count.
    private final AtomicLong sumDurationNanos = new AtomicLong();
    private final AtomicLong sumJitterNanos = new AtomicLong();

    TickMetrics() {}

    /** Called by the loop thread once per tick. Single-writer. */
    void recordTick(long durationNanos, long jitterNanos, boolean slow) {
        tickCount.increment();
        if (slow) {
            slowTickCount.increment();
        }
        lastDurationNanos.lazySet(durationNanos);
        lastJitterNanos.lazySet(jitterNanos);
        maxDurationNanos.accumulate(durationNanos);
        maxJitterNanos.accumulate(jitterNanos);
        sumDurationNanos.addAndGet(durationNanos);
        // Jitter can be negative (tick ran ahead of schedule); sum uses signed nanos.
        sumJitterNanos.addAndGet(jitterNanos);
    }

    public long tickCount() {
        return tickCount.sum();
    }

    public long slowTickCount() {
        return slowTickCount.sum();
    }

    public long lastDurationNanos() {
        return lastDurationNanos.get();
    }

    public long lastJitterNanos() {
        return lastJitterNanos.get();
    }

    public long maxDurationNanos() {
        return maxDurationNanos.get();
    }

    public long maxJitterNanos() {
        return maxJitterNanos.get();
    }

    /** Average tick duration in nanoseconds, or 0 if no ticks have run. */
    public long avgDurationNanos() {
        long ticks = tickCount.sum();
        return ticks == 0 ? 0 : sumDurationNanos.get() / ticks;
    }

    /** Average tick jitter in nanoseconds. Can be negative if the loop runs ahead of schedule. */
    public long avgJitterNanos() {
        long ticks = tickCount.sum();
        return ticks == 0 ? 0 : sumJitterNanos.get() / ticks;
    }
}
