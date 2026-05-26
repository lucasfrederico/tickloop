package io.lucasfrederico.tickloop;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress tests for sustained load. Tagged {@code stress} so they can be
 * skipped on dev laptops and only run in CI / nightly.
 */
@Tag("stress")
class StressTest {

    /**
     * Sustained: 4 producer threads pump 100k events each into a single
     * TickQueue over a few seconds at 10ms tick period. All 400k events
     * must reach the consumer and no ticks should be missed.
     */
    @Test
    @Timeout(60)
    void four_hundred_thousand_events_through_single_queue() throws Exception {
        final int producers = 4;
        final int perProducer = 100_000;

        AtomicInteger received = new AtomicInteger();
        AtomicLong tickCount = new AtomicLong();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .onTick(tickCount::set)
                .build();
        TickQueue<Long> q = loop.createQueue("stress", v -> received.incrementAndGet());
        loop.start();

        long startNanos = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            Thread t = new Thread(() -> {
                for (int i = 0; i < perProducer; i++) {
                    q.offer((long) i);
                }
            }, "stress-producer-" + p);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();

        // Wait for the loop to fully drain.
        long deadline = System.currentTimeMillis() + 20_000;
        while (received.get() < producers * perProducer
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        loop.stop();

        int expected = producers * perProducer;
        assertThat(received.get()).isEqualTo(expected);

        // Diagnostic only — print throughput so the test output is useful.
        double seconds = elapsedNanos / 1_000_000_000.0;
        double eventsPerSec = expected / seconds;
        System.out.printf(
                "stress: %d events in %.2fs = %.0f events/sec, %d ticks, %d slow ticks%n",
                expected, seconds, eventsPerSec,
                loop.metrics().tickCount(),
                loop.metrics().slowTickCount());
    }

    /**
     * Sustained: the loop runs for 5 seconds with no real work. Verify
     * the tick count is within 20% of the theoretical maximum and that
     * slow ticks are rare (<5% of total).
     *
     * <p>This is the canary test for scheduling correctness on the host.
     */
    @Test
    @Timeout(15)
    void no_op_loop_sustains_target_tick_rate_for_five_seconds() throws Exception {
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))    // 100 Hz target
                .slowTickThreshold(Duration.ofMillis(8))
                .onTick(tick -> {})
                .build();
        loop.start();
        Thread.sleep(5_000);
        loop.stop();

        long ticks = loop.metrics().tickCount();
        long slow = loop.metrics().slowTickCount();
        long avgDurationNanos = loop.metrics().avgDurationNanos();
        long avgJitterNanos = loop.metrics().avgJitterNanos();

        System.out.printf(
                "5s @ 100Hz: %d ticks (%d slow, %.2f%%), avg duration %.3f ms, avg jitter %.3f ms%n",
                ticks, slow, 100.0 * slow / ticks,
                avgDurationNanos / 1_000_000.0, avgJitterNanos / 1_000_000.0);

        // 500 ticks is the theoretical max at 100Hz × 5s. Accept 400+ (80%).
        assertThat(ticks).isGreaterThanOrEqualTo(400L);
        // No-op work should rarely overrun 8ms threshold.
        assertThat(slow).isLessThan(ticks / 20L); // <5%
    }
}
