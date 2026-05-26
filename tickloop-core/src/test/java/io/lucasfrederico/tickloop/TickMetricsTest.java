package io.lucasfrederico.tickloop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TickMetricsTest {

    @Test
    @Timeout(5)
    void counts_every_tick() throws Exception {
        AtomicLong sleepBudget = new AtomicLong(0);
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .onTick(tick -> {
                    long sleep = sleepBudget.get();
                    if (sleep > 0) Thread.sleep(sleep);
                })
                .build();
        loop.start();
        Thread.sleep(60);
        loop.stop();

        assertThat(loop.metrics().tickCount()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @Timeout(5)
    void counts_slow_ticks_separately() throws Exception {
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(50))
                .slowTickThreshold(Duration.ofMillis(10))
                .onTick(tick -> {
                    if (tick == 0) {
                        Thread.sleep(25); // slow on tick 0 only
                    }
                })
                .build();
        loop.start();
        Thread.sleep(150);
        loop.stop();

        TickMetrics m = loop.metrics();
        assertThat(m.slowTickCount()).isEqualTo(1L);
        assertThat(m.tickCount()).isGreaterThan(m.slowTickCount());
    }

    @Test
    @Timeout(5)
    void tracks_last_and_max_duration() throws Exception {
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(30))
                .onTick(tick -> {
                    if (tick == 0) Thread.sleep(15);
                })
                .build();
        loop.start();
        Thread.sleep(100);
        loop.stop();

        TickMetrics m = loop.metrics();
        assertThat(m.maxDurationNanos())
                .as("max duration should reflect the 15ms tick")
                .isGreaterThan(Duration.ofMillis(10).toNanos());
        // Last duration is from the most recent tick which is fast.
        assertThat(m.lastDurationNanos()).isLessThan(m.maxDurationNanos());
    }

    @Test
    @Timeout(5)
    void avg_duration_is_zero_before_any_tick() {
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofSeconds(1))
                .onTick(tick -> {})
                .build();
        assertThat(loop.metrics().avgDurationNanos()).isEqualTo(0L);
        assertThat(loop.metrics().tickCount()).isEqualTo(0L);
    }
}
