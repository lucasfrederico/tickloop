package io.lucasfrederico.tickloop;

import io.lucasfrederico.tickloop.internal.FixedBucketHistogram;

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
 * <p>v0.2.0 adds {@link #pDurationNanos(double)} and {@link #pJitterNanos(double)}
 * percentile queries backed by a fixed-bucket log-scale histogram (2x precision,
 * zero allocation on the hot path, no runtime dep on HdrHistogram).
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

    // Percentile histograms (v0.2.0).
    private final FixedBucketHistogram durationHistogram = new FixedBucketHistogram();
    private final FixedBucketHistogram jitterHistogram = new FixedBucketHistogram();

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
        // Percentile histograms only record non-negative values; negative jitter
        // is rare and would have to be remapped to 0 anyway.
        durationHistogram.record(durationNanos);
        jitterHistogram.record(Math.max(0L, jitterNanos));
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

    /**
     * Tick duration at percentile {@code p} (e.g. 0.99 for p99), in nanoseconds.
     * Returns the upper bound of the histogram bucket containing the percentile;
     * the actual value may be up to 2x smaller. See {@link FixedBucketHistogram}.
     */
    public long pDurationNanos(double p) {
        return durationHistogram.percentile(p);
    }

    /** Tick jitter at percentile {@code p}, in nanoseconds. Negative jitters are remapped to 0 before recording. */
    public long pJitterNanos(double p) {
        return jitterHistogram.percentile(p);
    }

    /** Shorthand: p50 of duration. */
    public long p50DurationNanos() { return pDurationNanos(0.50); }
    /** Shorthand: p95 of duration. */
    public long p95DurationNanos() { return pDurationNanos(0.95); }
    /** Shorthand: p99 of duration. */
    public long p99DurationNanos() { return pDurationNanos(0.99); }

    /** Shorthand: p50 of jitter. */
    public long p50JitterNanos() { return pJitterNanos(0.50); }
    /** Shorthand: p95 of jitter. */
    public long p95JitterNanos() { return pJitterNanos(0.95); }
    /** Shorthand: p99 of jitter. */
    public long p99JitterNanos() { return pJitterNanos(0.99); }
}
