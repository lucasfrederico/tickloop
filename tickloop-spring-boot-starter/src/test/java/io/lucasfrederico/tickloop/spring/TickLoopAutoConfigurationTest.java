package io.lucasfrederico.tickloop.spring;

import io.lucasfrederico.tickloop.TickHandler;
import io.lucasfrederico.tickloop.TickLoop;
import io.lucasfrederico.tickloop.TickMetrics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TickLoopAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TickLoopAutoConfiguration.class));

    @Test
    void no_handler_means_no_tickloop_bean() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TickLoop.class));
    }

    @Test
    void user_handler_triggers_tickloop_bean_creation() {
        runner.withUserConfiguration(HandlerConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(TickLoop.class);
            assertThat(ctx).hasSingleBean(TickMetrics.class);
            TickLoop loop = ctx.getBean(TickLoop.class);
            assertThat(loop.tickPeriod()).isEqualTo(Duration.ofMillis(50)); // default
        });
    }

    @Test
    void properties_are_applied() {
        runner.withUserConfiguration(HandlerConfig.class)
                .withPropertyValues(
                        "tickloop.tick-period=10ms",
                        "tickloop.thread-name=custom-loop",
                        "tickloop.auto-start=false"
                )
                .run(ctx -> {
                    TickLoop loop = ctx.getBean(TickLoop.class);
                    assertThat(loop.tickPeriod()).isEqualTo(Duration.ofMillis(10));
                    // autoStart=false means the loop should still be in NEW state
                    assertThat(loop.isRunning()).isFalse();
                });
    }

    @Test
    void disabled_skips_autoconfig() {
        runner.withUserConfiguration(HandlerConfig.class)
                .withPropertyValues("tickloop.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TickLoop.class));
    }

    @Test
    void user_provided_tickloop_bean_wins() {
        runner.withUserConfiguration(HandlerConfig.class, OverrideConfig.class)
                .run(ctx -> {
                    TickLoop loop = ctx.getBean(TickLoop.class);
                    // Our custom bean uses 999 ms tick period.
                    assertThat(loop.tickPeriod()).isEqualTo(Duration.ofMillis(999));
                });
    }

    @Configuration
    static class HandlerConfig {
        @Bean
        TickHandler myHandler() {
            AtomicLong counter = new AtomicLong();
            return tick -> counter.incrementAndGet();
        }
    }

    @Configuration
    static class OverrideConfig {
        @Bean
        TickLoop customLoop(TickHandler h) {
            return TickLoop.builder()
                    .tickPeriod(Duration.ofMillis(999))
                    .onTick(h)
                    .build();
        }
    }
}
