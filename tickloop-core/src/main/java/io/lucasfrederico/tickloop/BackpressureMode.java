package io.lucasfrederico.tickloop;

/**
 * How a bounded {@link TickQueue} reacts when full.
 *
 * <p>The unbounded default (when no capacity is set) is effectively
 * "grow forever, drop nothing" — fine for bursty traffic but a memory
 * leak risk under sustained overflow. Set a capacity + one of these
 * modes for production workloads.
 */
public enum BackpressureMode {

    /**
     * On a full queue, drop the oldest enqueued item to make room for
     * the new one. {@code offer} returns {@code true}.
     *
     * <p>Use when: most recent state matters more than full history (e.g.
     * "player position" updates — a 100-ms-old position is worthless if
     * a fresher one is available).
     */
    DROP_OLDEST,

    /**
     * On a full queue, drop the new item without enqueuing it.
     * {@code offer} returns {@code false}.
     *
     * <p>Use when: the consumer is overloaded and shedding new load is
     * preferable to falsifying history (e.g. metric samples — better
     * to skip a sample than rewrite the time series).
     */
    DROP_NEWEST,

    /**
     * On a full queue, block the producer thread until space is available.
     *
     * <p>Use with extreme care: this couples the producer to the consumer's
     * tick latency. Never use from the loop thread itself or you'll deadlock.
     * Reasonable for low-rate ingestion threads where stalling is acceptable.
     */
    BLOCK,

    /**
     * On a full queue, throw {@link QueueFullException} from {@code offer}.
     *
     * <p>Use when: the caller has a sensible response to a full queue
     * (retry with backoff, route elsewhere, log + drop with a metric)
     * and silent dropping is unacceptable.
     */
    FAIL_FAST
}
