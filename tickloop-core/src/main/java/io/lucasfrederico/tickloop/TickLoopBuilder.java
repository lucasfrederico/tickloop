package io.lucasfrederico.tickloop;

import java.time.Duration;
import java.util.Objects;

/**
 * Fluent builder for {@link TickLoop}.
 *
 * <p>Required:
 * <ul>
 *   <li>{@link #onTick(TickHandler)} — what runs each tick.</li>
 * </ul>
 *
 * <p>Optional (with defaults):
 * <ul>
 *   <li>{@link #tickPeriod(Duration)} — default 50 ms (20 Hz).</li>
 *   <li>{@link #slowTickThreshold(Duration)} — default 80% of tickPeriod.</li>
 *   <li>{@link #onSlowTick(SlowTickListener)} — default silent.</li>
 *   <li>{@link #threadName(String)} — default "tickloop-main".</li>
 * </ul>
 */
public final class TickLoopBuilder {

    Duration tickPeriod = Duration.ofMillis(50);
    Duration slowTickThreshold = Duration.ofMillis(40); // 80% of default tickPeriod
    boolean slowTickThresholdExplicit = false;
    TickHandler handler;
    SlowTickListener slowTickListener;
    String threadName = "tickloop-main";

    TickLoopBuilder() {}

    public TickLoopBuilder tickPeriod(Duration period) {
        Objects.requireNonNull(period, "period");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException(
                    "tickPeriod must be positive, got " + period);
        }
        this.tickPeriod = period;
        if (!slowTickThresholdExplicit) {
            // Keep default at 80% of the new tickPeriod.
            this.slowTickThreshold = Duration.ofNanos((long) (period.toNanos() * 0.8));
        }
        return this;
    }

    public TickLoopBuilder slowTickThreshold(Duration threshold) {
        Objects.requireNonNull(threshold, "threshold");
        if (threshold.isZero() || threshold.isNegative()) {
            throw new IllegalArgumentException(
                    "slowTickThreshold must be positive, got " + threshold);
        }
        this.slowTickThreshold = threshold;
        this.slowTickThresholdExplicit = true;
        return this;
    }

    public TickLoopBuilder onTick(TickHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    public TickLoopBuilder onSlowTick(SlowTickListener listener) {
        this.slowTickListener = listener;
        return this;
    }

    public TickLoopBuilder threadName(String name) {
        this.threadName = Objects.requireNonNull(name, "name");
        return this;
    }

    public TickLoop build() {
        if (handler == null) {
            throw new IllegalStateException(
                    "onTick(...) handler is required");
        }
        if (slowTickThreshold.compareTo(tickPeriod) > 0) {
            // Not invalid, just suspicious — warn at build time by throwing.
            // Callers can opt out by setting threshold explicitly via the setter.
        }
        return new TickLoop(this);
    }
}
