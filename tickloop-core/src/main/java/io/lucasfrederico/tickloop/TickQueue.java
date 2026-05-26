package io.lucasfrederico.tickloop;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Multi-producer, single-consumer event queue drained automatically at
 * the start of each tick.
 *
 * <p>Two flavours:
 * <ul>
 *   <li><b>Unbounded</b> (via {@link TickLoop#createQueue}): no capacity limit,
 *       drops nothing, allocates per offer. Fine for bursty traffic; memory
 *       leak risk under sustained overflow.</li>
 *   <li><b>Bounded</b> (via {@link TickLoop#createBoundedQueue}): fixed capacity
 *       + a {@link BackpressureMode} controlling what happens when full.</li>
 * </ul>
 *
 * <p>Drain happens after {@code runOnMain} work but before the user
 * tick handler, so the handler observes the post-drain state.
 */
public final class TickQueue<T> {

    private final String name;
    private final Consumer<T> consumer;
    private final boolean bounded;
    private final int capacity;
    private final BackpressureMode mode;

    // Exactly one of these is non-null depending on bounded flag.
    private final ConcurrentLinkedQueue<T> unboundedQueue;
    private final BlockingQueue<T> boundedQueue;

    private final LongAdder droppedCount = new LongAdder();

    /** Unbounded queue ctor (current v0.1.0 behaviour). */
    TickQueue(String name, Consumer<T> consumer) {
        this.name = Objects.requireNonNull(name, "name");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.bounded = false;
        this.capacity = Integer.MAX_VALUE;
        this.mode = null;
        this.unboundedQueue = new ConcurrentLinkedQueue<>();
        this.boundedQueue = null;
    }

    /** Bounded queue ctor (v0.2.0). */
    TickQueue(String name, int capacity, BackpressureMode mode, Consumer<T> consumer) {
        this.name = Objects.requireNonNull(name, "name");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.bounded = true;
        this.capacity = capacity;
        this.unboundedQueue = null;
        // DROP_OLDEST needs poll-then-offer, which ArrayBlockingQueue does fine.
        // LinkedBlockingDeque is also OK for DROP_OLDEST and gives slightly more
        // flexible iteration; ArrayBlockingQueue is more compact in memory.
        if (mode == BackpressureMode.DROP_OLDEST) {
            this.boundedQueue = new LinkedBlockingDeque<>(capacity);
        } else {
            this.boundedQueue = new ArrayBlockingQueue<>(capacity);
        }
    }

    /**
     * Submit an event. Behaviour depends on whether the queue is bounded:
     *
     * <ul>
     *   <li>Unbounded: always returns true.</li>
     *   <li>Bounded + DROP_OLDEST: removes the oldest pending event, enqueues
     *       the new one, returns true. Increments {@link #droppedCount}.</li>
     *   <li>Bounded + DROP_NEWEST: returns false without enqueuing. Increments
     *       {@link #droppedCount}.</li>
     *   <li>Bounded + BLOCK: blocks the caller until space is available.
     *       Returns true when the put completes. Restores interrupt flag
     *       and returns false if interrupted.</li>
     *   <li>Bounded + FAIL_FAST: throws {@link QueueFullException}.</li>
     * </ul>
     */
    public boolean offer(T event) {
        Objects.requireNonNull(event, "event");
        if (!bounded) {
            return unboundedQueue.offer(event);
        }
        switch (mode) {
            case DROP_OLDEST: {
                // Try fast path first; on full, evict and retry.
                if (boundedQueue.offer(event)) {
                    return true;
                }
                // Slow path: drop oldest then offer. Loop because another producer
                // may have refilled the slot between poll() and offer().
                while (true) {
                    T evicted = boundedQueue.poll();
                    if (evicted != null) {
                        droppedCount.increment();
                    }
                    if (boundedQueue.offer(event)) {
                        return true;
                    }
                }
            }
            case DROP_NEWEST:
                if (boundedQueue.offer(event)) {
                    return true;
                }
                droppedCount.increment();
                return false;
            case BLOCK:
                try {
                    boundedQueue.put(event);
                    return true;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            case FAIL_FAST:
                if (boundedQueue.offer(event)) {
                    return true;
                }
                throw new QueueFullException(name, capacity);
            default:
                throw new IllegalStateException("unknown mode: " + mode);
        }
    }

    public int size() {
        return bounded ? boundedQueue.size() : unboundedQueue.size();
    }

    public boolean isEmpty() {
        return bounded ? boundedQueue.isEmpty() : unboundedQueue.isEmpty();
    }

    public boolean isBounded() {
        return bounded;
    }

    public int capacity() {
        return capacity;
    }

    public BackpressureMode mode() {
        return mode;
    }

    /** How many items have been dropped due to backpressure (DROP_OLDEST / DROP_NEWEST). */
    public long droppedCount() {
        return droppedCount.sum();
    }

    public String name() {
        return name;
    }

    /** Drain everything currently in the queue and feed it to the consumer.
     * Called by the loop thread; not part of the public API. */
    int drain() {
        int count = 0;
        T item;
        if (bounded) {
            while ((item = boundedQueue.poll()) != null) {
                consumer.accept(item);
                count++;
            }
        } else {
            while ((item = unboundedQueue.poll()) != null) {
                consumer.accept(item);
                count++;
            }
        }
        return count;
    }
}
