package io.lucasfrederico.tickloop;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Fixed-rate tick scheduler that owns a single "loop thread" and ticks at
 * a configurable rate (default 20 Hz = 50 ms).
 *
 * <p>The contract:
 * <ul>
 *   <li>Exactly one thread (the loop thread) runs all {@link TickHandler}
 *       invocations and any work submitted via {@link #runOnMain} or
 *       continuations of {@link #offload}.</li>
 *   <li>Tick scheduling is absolute, not relative: drift accumulates against
 *       the loop's start instant, so a sequence of slow ticks does not
 *       permanently push the schedule late.</li>
 *   <li>If a tick overruns its target period, the loop runs the next tick
 *       immediately (no padding sleep) and reports a slow tick.</li>
 *   <li>{@link #stop()} signals the loop to exit at the next tick boundary,
 *       blocks until the loop thread terminates, then shuts down the
 *       offload pool (with a short grace period for in-flight work).</li>
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
    private final boolean useVirtualThreadsForOffload;

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private volatile Thread loopThread;
    private volatile long currentTick = 0L;

    // Work submitted from any thread; drained at the start of each tick.
    private final ConcurrentLinkedQueue<Runnable> mainQueue = new ConcurrentLinkedQueue<>();

    // Registered TickQueues; drained on each tick in registration order.
    private final CopyOnWriteArrayList<TickQueue<?>> tickQueues = new CopyOnWriteArrayList<>();

    // Aggregate stats (updated on the loop thread, read by any thread).
    private final TickMetrics metrics = new TickMetrics();

    // Off-thread work pool. Daemon threads so they don't keep the JVM alive
    // after the loop stops. Created lazily on first offload to avoid spinning
    // up threads when the user never uses the offload API.
    private volatile ExecutorService offloadPool;
    private final AtomicLong offloadThreadCounter = new AtomicLong();

    private enum State { NEW, RUNNING, STOPPING, STOPPED }

    TickLoop(TickLoopBuilder b) {
        this.tickPeriod = b.tickPeriod;
        this.tickPeriodNanos = b.tickPeriod.toNanos();
        this.slowTickThresholdNanos = b.slowTickThreshold.toNanos();
        this.handler = b.handler;
        this.slowTickListener = b.slowTickListener;
        this.threadName = b.threadName;
        this.useVirtualThreadsForOffload = b.useVirtualThreadsForOffload;
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
     * Signals the loop to stop at the next tick boundary, waits for the
     * loop thread to terminate, then shuts down the offload pool.
     *
     * @return true if the loop terminated within the timeout
     */
    public boolean stop(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        state.compareAndSet(State.RUNNING, State.STOPPING);
        Thread t = loopThread;
        boolean loopExited = true;
        if (t != null) {
            LockSupport.unpark(t);
            t.join(timeout.toMillis());
            loopExited = !t.isAlive();
        }
        ExecutorService pool = offloadPool;
        if (pool != null) {
            pool.shutdown();
            // Don't block on offload pool cleanup beyond a small grace; the
            // caller already gave us their budget on the loop join above.
            pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
        }
        return loopExited;
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

    /**
     * Submit work to run on the loop thread at the start of the next tick.
     * Safe to call from any thread, including the loop thread itself.
     *
     * <p>If the loop is not yet started or has already stopped, the work
     * is enqueued and will run if and when the loop runs next; if the
     * loop never runs again, the work is silently dropped on stop.
     */
    public void runOnMain(Runnable work) {
        Objects.requireNonNull(work, "work");
        mainQueue.offer(work);
    }

    /**
     * Create and register a {@link TickQueue} that will be drained at the
     * start of every tick. Each drained event is fed to {@code consumer}
     * on the loop thread.
     *
     * <p>Typical use:
     * <pre>{@code
     * TickQueue<NetworkEvent> incoming =
     *     loop.createQueue("network", this::handleNetworkEvent);
     * // From a network handler thread:
     * incoming.offer(new ConnectEvent(socket));
     * }</pre>
     */
    public <T> TickQueue<T> createQueue(String name, Consumer<T> consumer) {
        TickQueue<T> q = new TickQueue<>(name, consumer);
        tickQueues.add(q);
        return q;
    }

    /**
     * Create and register a bounded {@link TickQueue} with the given capacity
     * and {@link BackpressureMode}.
     *
     * <p>Choose the mode based on what the workload tolerates:
     * <ul>
     *   <li>{@link BackpressureMode#DROP_OLDEST}: keep most recent samples.</li>
     *   <li>{@link BackpressureMode#DROP_NEWEST}: prefer history fidelity.</li>
     *   <li>{@link BackpressureMode#BLOCK}: producer-consumer coupling, careful.</li>
     *   <li>{@link BackpressureMode#FAIL_FAST}: caller handles overflow explicitly.</li>
     * </ul>
     */
    public <T> TickQueue<T> createBoundedQueue(
            String name, int capacity, BackpressureMode mode, Consumer<T> consumer) {
        TickQueue<T> q = new TickQueue<>(name, capacity, mode, consumer);
        tickQueues.add(q);
        return q;
    }

    /** Aggregate metrics (tick counts, latencies, jitter). Safe to read from any thread. */
    public TickMetrics metrics() {
        return metrics;
    }

    /**
     * Run {@code blocking} on the offload pool (separate from the loop
     * thread) and return a handle whose {@code thenOnMain} continuation
     * runs back on the loop thread when the work completes.
     *
     * <p>Typical use:
     * <pre>{@code
     * loop.offload(() -> database.loadProfile(id))
     *     .thenOnMain(profile -> player.applyProfile(profile));
     * }</pre>
     *
     * <p>The offload pool is created lazily and uses daemon threads.
     */
    public <T> OffloadResult<T> offload(Supplier<T> blocking) {
        Objects.requireNonNull(blocking, "blocking");
        ExecutorService pool = offloadPool();
        CompletableFuture<T> future = CompletableFuture.supplyAsync(blocking, pool);
        return new OffloadResult<>(future, this);
    }

    // -- internal -------------------------------------------------------

    private ExecutorService offloadPool() {
        ExecutorService pool = offloadPool;
        if (pool != null) {
            return pool;
        }
        synchronized (this) {
            if (offloadPool != null) {
                return offloadPool;
            }
            if (useVirtualThreadsForOffload) {
                ThreadFactory vtf = Thread.ofVirtual()
                        .name(threadName + "-offload-vt-", 1)
                        .factory();
                offloadPool = Executors.newThreadPerTaskExecutor(vtf);
            } else {
                ThreadFactory tf = r -> {
                    Thread t = new Thread(r,
                            threadName + "-offload-" + offloadThreadCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                };
                offloadPool = Executors.newCachedThreadPool(tf);
            }
            return offloadPool;
        }
    }

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

                // Drain pending runOnMain work before TickQueues so any
                // continuations from previous-tick offloads land before
                // queue handlers observe state.
                drainMainQueue();

                // Drain registered TickQueues. Each queue is fully drained
                // before the next; the user handler runs after all queues.
                for (TickQueue<?> q : tickQueues) {
                    try {
                        q.drain();
                    } catch (RuntimeException ex) {
                        Thread.currentThread()
                                .getUncaughtExceptionHandler()
                                .uncaughtException(Thread.currentThread(), ex);
                    }
                }

                try {
                    handler.onTick(tick);
                } catch (Exception ex) {
                    Thread.currentThread()
                            .getUncaughtExceptionHandler()
                            .uncaughtException(Thread.currentThread(), ex);
                }

                final long finishedAt = System.nanoTime();
                final long duration = finishedAt - startedAt;
                final long jitter = startedAt - scheduledAt;
                final boolean slow = duration > slowTickThresholdNanos;
                metrics.recordTick(duration, jitter, slow);

                if (slow && slowTickListener != null) {
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
            // Final drain on shutdown so submitted work doesn't disappear
            // silently if it landed between the last tick and stop().
            drainMainQueue();
            state.set(State.STOPPED);
        }
    }

    private void drainMainQueue() {
        Runnable r;
        while ((r = mainQueue.poll()) != null) {
            try {
                r.run();
            } catch (RuntimeException ex) {
                Thread.currentThread()
                        .getUncaughtExceptionHandler()
                        .uncaughtException(Thread.currentThread(), ex);
            }
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
                return;
            }
        }
    }
}
