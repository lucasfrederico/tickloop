package io.lucasfrederico.tickloop;

/**
 * Thrown by {@link TickQueue#offer} when the queue is bounded with
 * {@link BackpressureMode#FAIL_FAST} and full.
 *
 * <p>Unchecked because backpressure decisions usually propagate
 * upward as "request rejected" results, not handled inline.
 */
public class QueueFullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String queueName;
    private final int capacity;

    public QueueFullException(String queueName, int capacity) {
        super("TickQueue '" + queueName + "' is full (capacity=" + capacity + ")");
        this.queueName = queueName;
        this.capacity = capacity;
    }

    public String queueName() {
        return queueName;
    }

    public int capacity() {
        return capacity;
    }
}
