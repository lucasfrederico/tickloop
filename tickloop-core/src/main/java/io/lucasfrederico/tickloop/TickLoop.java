package io.lucasfrederico.tickloop;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Fixed-rate tick scheduler that owns a single "loop thread" and ticks at
 * a configurable rate (default 20 Hz = 50 ms).
 *
 * <p>The contract:
 * <ul>
 *   <li>Exactly one thread (the loop thread) runs all {@link TickHandler}
 *       invocations and any work submitted via the loop's APIs.</li>
 *   <li>Tick scheduling is absolute, not relative: drift accumulates against
 *       the loop's start instant, so a sequence of slow ticks does not
 *       permanently push the schedule late.</li>
 *   <li>If a tick overruns its target period, the loop runs the next tick
 *       immediately (no padding sleep) and reports a slow tick.</li>
 *   <li>{@link #stop()} signals the loop to exit at the next tick boundary
 *       and blocks until the loop thread terminates.</li>
 * </ul>
 *
 * <p>Use {@link #builder()} to construct.
 */
public final class TickLoop {

    private final Duration tickPeriod;
    private final long tickPeriodNanos;
    private final long slowTickThresholdNanos;
    private final TickHandler handler;
    private final SlowTickListener slowTickListener;
    private final String threadName;

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private volatile Thread loopThread;
    private volatile long currentTick = 0L;

    private enum State { NEW, RUNNING, STOPPING, STOPPED }

    TickLoop(TickLoopBuilder b) {
        this.tickPeriod = b.tickPeriod;
        this.tickPeriodNanos = b.tickPeriod.toNanos();
        this.slowTickThresholdNanos = b.slowTickThreshold.toNanos();
        this.handler = b.handler;
        this.slowTickListener = b.slowTickListener;
        this.threadName = b.threadName;
    }

    public static TickLoopBuilder builder() {
        return new TickLoopBuilder();
    }

    /** Starts the loop thread. Returns immediately. Idempotent: a second
     * call when already running throws. */
    public void start() {
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            throw new IllegalStateException(
                    "TickLoop already started (state=" + state.get() + ")");
        }
        Thread t = new Thread(this::runLoop, threadName);
        t.setDaemon(false);
        loopThread = t;
        t.start();
    }

    /**
     * Signals the loop to stop at the next tick boundary and waits up to
     * the given timeout for the loop thread to terminate.
     *
     * @return true if the loop terminated within the timeout
     */
    public boolean stop(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        state.compareAndSet(State.RUNNING, State.STOPPING);
        Thread t = loopThread;
        if (t == null) {
            return true;
        }
        // Wake the loop if it's parked between ticks.
        LockSupport.unpark(t);
        t.join(timeout.toMillis());
        return !t.isAlive();
    }

    /** Convenience: stop with a 5-second default timeout. */
    public boolean stop() throws InterruptedException {
        return stop(Duration.ofSeconds(5));
    }

    /** Returns true iff the loop thread is currently running. */
    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /** Current tick number. Monotonic; starts at 0 on the first tick. */
    public long currentTick() {
        return currentTick;
    }

    /** Configured tick period. */
    public Duration tickPeriod() {
        return tickPeriod;
    }

    // -- internal -------------------------------------------------------

    private void runLoop() {
        final long startNanos = System.nanoTime();
        long tick = 0L;
        try {
            while (state.get() == State.RUNNING) {
                final long scheduledAt = startNanos + tick * tickPeriodNanos;
                parkUntil(scheduledAt);
                if (state.get() != State.RUNNING) {
                    break;
                }
                final long startedAt = System.nanoTime();
                currentTick = tick;
                try {
                    handler.onTick(tick);
                } catch (Exception ex) {
                    // Uncaught: report to default handler, keep ticking.
                    Thread.currentThread()
                            .getUncaughtExceptionHandler()
                            .uncaughtException(Thread.currentThread(), ex);
                }
                final long finishedAt = System.nanoTime();
                final long duration = finishedAt - startedAt;
                if (duration > slowTickThresholdNanos && slowTickListener != null) {
                    try {
                        slowTickListener.onSlowTick(new TickStats(
                                tick, scheduledAt, startedAt, duration, tickPeriodNanos));
                    } catch (RuntimeException listenerEx) {
                        Thread.currentThread()
                                .getUncaughtExceptionHandler()
                                .uncaughtException(Thread.currentThread(), listenerEx);
                    }
                }
                tick++;
            }
        } finally {
            state.set(State.STOPPED);
        }
    }

    /**
     * Parks the loop thread until {@code targetNanos} (a {@code System.nanoTime()}
     * value) is reached. Returns immediately if already past the target —
     * this is the slow-tick recovery path.
     */
    private static void parkUntil(long targetNanos) {
        while (true) {
            final long now = System.nanoTime();
            final long remaining = targetNanos - now;
            if (remaining <= 0) {
                return;
            }
            LockSupport.parkNanos(remaining);
            if (Thread.interrupted()) {
                // Drop the interrupt flag; the loop checks state separately.
                return;
            }
        }
    }
}
