package io.lucasfrederico.tickloop;

/**
 * Snapshot of a single tick's timing.
 *
 * <p>Passed to {@link SlowTickListener#onSlowTick(TickStats)} when a tick
 * overruns its target budget, and exposed through {@link TickMetrics} for
 * aggregate statistics.
 *
 * @param tickNumber  monotonic counter, starting at 0 for the first tick
 * @param scheduledAtNanos  {@code System.nanoTime()} at which the tick was
 *                          scheduled to start (target instant)
 * @param startedAtNanos  {@code System.nanoTime()} when the tick actually
 *                        began executing user work; may be after
 *                        {@code scheduledAtNanos} if a prior tick overran
 * @param durationNanos  duration of the user-visible work portion of the
 *                       tick (does not include sleep/wait time between ticks)
 * @param targetPeriodNanos  configured tick period (e.g. 50_000_000 for 50 ms)
 */
public record TickStats(
        long tickNumber,
        long scheduledAtNanos,
        long startedAtNanos,
        long durationNanos,
        long targetPeriodNanos
) {

    /**
     * How late this tick was relative to its scheduled instant. Positive
     * values mean the loop fell behind; zero or negative means on-time.
     */
    public long jitterNanos() {
        return startedAtNanos - scheduledAtNanos;
    }

    /** Convenience: tick duration in milliseconds. */
    public double durationMs() {
        return durationNanos / 1_000_000.0;
    }

    /** Convenience: jitter in milliseconds. */
    public double jitterMs() {
        return jitterNanos() / 1_000_000.0;
    }
}
