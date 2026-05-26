package io.lucasfrederico.tickloop;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Multi-producer, single-consumer event queue drained automatically at
 * the start of each tick.
 *
 * <p>Producers (any thread) call {@link #offer} to submit an event.
 * The loop thread, on each tick, drains every available event and
 * passes it to the consumer registered when the queue was created.
 *
 * <p>Drain happens after {@code runOnMain} work but before the user
 * tick handler, so the handler observes the post-drain state.
 *
 * <p>Implementation note for v0.1.0: backed by {@link ConcurrentLinkedQueue}
 * — correct and lock-free, but linked-node allocation per offer. If
 * benchmarks show GC pressure in a production setting, this can be
 * swapped for a Disruptor-style MPSC array queue without changing the
 * public API.
 */
public final class TickQueue<T> {

    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    private final Consumer<T> consumer;
    private final String name;

    TickQueue(String name, Consumer<T> consumer) {
        this.name = Objects.requireNonNull(name, "name");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    /** Submit an event. Returns false only if the queue is closed (currently always returns true). */
    public boolean offer(T event) {
        Objects.requireNonNull(event, "event");
        return queue.offer(event);
    }

    /** Approximate number of pending events. O(n) on {@link ConcurrentLinkedQueue}; use sparingly. */
    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public String name() {
        return name;
    }

    /** Drain everything currently in the queue and feed it to the consumer.
     * Called by the loop thread; not part of the public API. */
    int drain() {
        int count = 0;
        T item;
        while ((item = queue.poll()) != null) {
            consumer.accept(item);
            count++;
        }
        return count;
    }
}
