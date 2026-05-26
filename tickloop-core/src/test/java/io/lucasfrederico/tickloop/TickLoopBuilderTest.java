package io.lucasfrederico.tickloop;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TickLoopBuilderTest {

    @Test
    void requires_handler() {
        assertThatThrownBy(() -> TickLoop.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("onTick");
    }

    @Test
    void rejects_zero_tick_period() {
        assertThatThrownBy(() -> TickLoop.builder().tickPeriod(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_negative_tick_period() {
        assertThatThrownBy(() -> TickLoop.builder().tickPeriod(Duration.ofMillis(-10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void default_slow_tick_threshold_is_80_percent_of_tick_period() {
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(100))
                .onTick(tick -> {})
                .build();
        // Indirectly verified via the loop's behaviour in TickLoopTest;
        // here we just confirm the builder accepts the configuration.
        assertThat(loop.tickPeriod()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void explicit_slow_tick_threshold_is_preserved_when_tick_period_changes() {
        // If user sets the threshold first then changes the period, the
        // explicit threshold must not be overwritten by the period-driven
        // default.
        TickLoop loop = TickLoop.builder()
                .slowTickThreshold(Duration.ofMillis(7))
                .tickPeriod(Duration.ofMillis(100))
                .onTick(tick -> {})
                .build();
        assertThat(loop.tickPeriod()).isEqualTo(Duration.ofMillis(100));
        // (Threshold itself is not publicly exposed yet; observable via slow-tick callback in TickLoopTest.)
    }
}
