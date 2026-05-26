package io.lucasfrederico.tickloop;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A bundle of named {@link TickLoop} instances running at independent
 * tick rates, started and stopped together.
 *
 * <p>Typical use case: a game backend that runs physics at 60 Hz, AI at
 * 5 Hz, and a persistence snapshot at 0.1 Hz, all sharing the same set of
 * connected players.
 *
 * <pre>{@code
 * TickGroup group = TickGroup.builder()
 *     .addLoop("physics",
 *         TickLoop.builder().tickPeriod(Duration.ofMillis(16))
 *             .onTick(this::physicsUpdate))
 *     .addLoop("ai",
 *         TickLoop.builder().tickPeriod(Duration.ofMillis(200))
 *             .onTick(this::aiUpdate))
 *     .addLoop("snapshot",
 *         TickLoop.builder().tickPeriod(Duration.ofSeconds(10))
 *             .onTick(this::snapshot))
 *     .build();
 *
 * group.start();
 * // ... later ...
 * group.stop();
 * }</pre>
 *
 * <p><b>Cross-loop messaging.</b> Each loop owns its own state. To send
 * an event from one loop to another, register a {@link TickQueue} on the
 * target loop and have the source loop {@code offer} to it. {@code TickQueue}
 * is thread-safe and the cross-thread hop is well-defined: the target loop
 * sees the event on its next tick.
 *
 * <pre>{@code
 * TickQueue<DamageEvent> physicsInbox = group.loop("physics")
 *     .createQueue("damage", event -> applyDamage(event));
 *
 * // From the AI loop, the network handler, anywhere:
 * physicsInbox.offer(new DamageEvent(playerId, 10));
 * }</pre>
 */
public final class TickGroup {

    private final Map<String, TickLoop> loops;

    TickGroup(Map<String, TickLoop> loops) {
        this.loops = Collections.unmodifiableMap(new LinkedHashMap<>(loops));
    }

    public static TickGroupBuilder builder() {
        return new TickGroupBuilder();
    }

    /** Get a loop by the name it was registered under. Throws if unknown. */
    public TickLoop loop(String name) {
        TickLoop l = loops.get(name);
        if (l == null) {
            throw new IllegalArgumentException(
                    "no loop named '" + name + "' in this group; available: " + loops.keySet());
        }
        return l;
    }

    /** All loops, in registration order. */
    public Map<String, TickLoop> loops() {
        return loops;
    }

    /** Start every loop. Loops boot in registration order. */
    public void start() {
        for (TickLoop l : loops.values()) {
            l.start();
        }
    }

    /**
     * Stop every loop, giving each up to {@code timeout} to terminate.
     * Loops are stopped in registration order; offload pools shut down with
     * their loops.
     *
     * @return true iff every loop terminated cleanly within its budget
     */
    public boolean stop(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        boolean allClean = true;
        for (TickLoop l : loops.values()) {
            if (!l.stop(timeout)) {
                allClean = false;
            }
        }
        return allClean;
    }

    /** Convenience: stop with a 5-second budget per loop. */
    public boolean stop() throws InterruptedException {
        return stop(Duration.ofSeconds(5));
    }

    /** True iff every loop in the group reports running. */
    public boolean isAllRunning() {
        for (TickLoop l : loops.values()) {
            if (!l.isRunning()) return false;
        }
        return true;
    }
}
