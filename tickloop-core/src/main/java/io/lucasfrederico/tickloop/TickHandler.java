package io.lucasfrederico.tickloop;

/**
 * Per-tick user callback. Runs on the loop thread, with the tick number
 * available for scheduling decisions (e.g. "every 5 ticks, run AI; every
 * 60 ticks, persist a snapshot").
 *
 * <p>Any exception thrown propagates to the loop thread's uncaught
 * exception handler; the loop continues with the next tick. To halt the
 * loop on errors, call {@link TickLoop#stop()} from the handler.
 */
@FunctionalInterface
public interface TickHandler {
    void onTick(long tickNumber) throws Exception;
}
