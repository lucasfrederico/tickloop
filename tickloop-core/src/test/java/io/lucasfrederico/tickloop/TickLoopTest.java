package io.lucasfrederico.tickloop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TickLoopTest {

    @Test
    @Timeout(5)
    void runs_expected_number_of_ticks() throws Exception {
        AtomicLong counter = new AtomicLong();
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .onTick(tick -> counter.incrementAndGet())
                .build();

        loop.start();
        Thread.sleep(120); // ~12 ticks at 10ms period
        loop.stop();

        // Expect at least 8 ticks (allowing for scheduler jitter and stop overhead).
        assertThat(counter.get()).isBetween(8L, 20L);
    }

    @Test
    @Timeout(5)
    void tick_number_is_monotonic_and_starts_at_zero() throws Exception {
        AtomicLong lastTick = new AtomicLong(-1);
        AtomicReference<String> violation = new AtomicReference<>();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(5))
                .onTick(tick -> {
                    long prev = lastTick.get();
                    if (tick != prev + 1) {
                        violation.compareAndSet(null,
                                "expected " + (prev + 1) + " got " + tick);
                    }
                    lastTick.set(tick);
                })
                .build();

        loop.start();
        Thread.sleep(50);
        loop.stop();

        assertThat(violation.get()).isNull();
        assertThat(lastTick.get()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @Timeout(5)
    void slow_tick_triggers_listener() throws Exception {
        CountDownLatch slowTickFired = new CountDownLatch(1);
        AtomicReference<TickStats> reported = new AtomicReference<>();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(50))
                .slowTickThreshold(Duration.ofMillis(10))
                .onTick(tick -> {
                    if (tick == 0) {
                        Thread.sleep(30); // intentionally overrun the 10ms threshold
                    }
                })
                .onSlowTick(stats -> {
                    reported.compareAndSet(null, stats);
                    slowTickFired.countDown();
                })
                .build();

        loop.start();
        boolean fired = slowTickFired.await(2, TimeUnit.SECONDS);
        loop.stop();

        assertThat(fired).as("slow tick listener should fire within 2s").isTrue();
        TickStats stats = reported.get();
        assertThat(stats.tickNumber()).isEqualTo(0L);
        assertThat(stats.durationNanos()).isGreaterThan(Duration.ofMillis(20).toNanos());
    }

    @Test
    @Timeout(5)
    void fast_ticks_do_not_trigger_slow_tick_listener() throws Exception {
        AtomicInteger slowTickCount = new AtomicInteger();
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(50))
                .slowTickThreshold(Duration.ofMillis(40))
                .onTick(tick -> {}) // ~0ms work
                .onSlowTick(stats -> slowTickCount.incrementAndGet())
                .build();

        loop.start();
        Thread.sleep(250); // ~5 ticks
        loop.stop();

        assertThat(slowTickCount.get())
                .as("no-op ticks should never exceed threshold")
                .isEqualTo(0);
    }

    @Test
    @Timeout(5)
    void exception_in_handler_does_not_kill_the_loop() throws Exception {
        AtomicInteger tickCount = new AtomicInteger();
        AtomicInteger throwCount = new AtomicInteger();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .onTick(tick -> {
                    tickCount.incrementAndGet();
                    if (tick % 2 == 0) {
                        throwCount.incrementAndGet();
                        throw new RuntimeException("synthetic error on tick " + tick);
                    }
                })
                .build();

        // Suppress the stderr noise from the default uncaught-exception handler.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {});
        try {
            loop.start();
            Thread.sleep(100);
            loop.stop();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(null);
        }

        assertThat(tickCount.get()).isGreaterThan(3);
        assertThat(throwCount.get()).isGreaterThan(0);
    }

    @Test
    @Timeout(5)
    void cannot_start_twice() {
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofSeconds(1))
                .onTick(tick -> {})
                .build();
        loop.start();
        try {
            assertThatThrownBy(loop::start)
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            try { loop.stop(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Test
    @Timeout(5)
    void is_running_reflects_state() throws Exception {
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(20))
                .onTick(tick -> {})
                .build();

        assertThat(loop.isRunning()).isFalse();
        loop.start();
        // small wait so the thread reaches its first tick before we observe
        Thread.sleep(5);
        assertThat(loop.isRunning()).isTrue();
        loop.stop();
        assertThat(loop.isRunning()).isFalse();
    }
}
