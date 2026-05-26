package io.lucasfrederico.tickloop;

/**
 * Callback invoked when a tick's work duration exceeds the configured
 * slow-tick threshold.
 *
 * <p>Invoked on the loop thread, immediately after the slow tick completes.
 * Keep the implementation cheap (logging, metric increment) — anything
 * heavier should be deferred via {@link MainThreadScope#offload}.
 *
 * <p>Default behaviour when no listener is configured: silent.
 */
@FunctionalInterface
public interface SlowTickListener {
    void onSlowTick(TickStats stats);
}
