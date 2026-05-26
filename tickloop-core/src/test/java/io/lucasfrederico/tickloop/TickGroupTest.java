package io.lucasfrederico.tickloop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TickGroupTest {

    @Test
    @Timeout(5)
    void two_loops_run_at_different_rates() throws Exception {
        AtomicLong fastTicks = new AtomicLong();
        AtomicLong slowTicks = new AtomicLong();

        TickGroup group = TickGroup.builder()
                .addLoop("fast", TickLoop.builder()
                        .tickPeriod(Duration.ofMillis(10))
                        .onTick(t -> fastTicks.incrementAndGet()))
                .addLoop("slow", TickLoop.builder()
                        .tickPeriod(Duration.ofMillis(50))
                        .onTick(t -> slowTicks.incrementAndGet()))
                .build();

        group.start();
        Thread.sleep(300);
        group.stop();

        // fast at 10ms should fire ~5x more than slow at 50ms.
        // Generous bounds for CI jitter: fast ≥ 2× slow.
        assertThat(fastTicks.get())
                .as("fast loop (10ms) should tick more often than slow loop (50ms)")
                .isGreaterThan(slowTicks.get() * 2);
    }

    @Test
    @Timeout(5)
    void cross_loop_messaging_via_tickqueue_works() throws Exception {
        AtomicInteger received = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(5);

        TickGroup group = TickGroup.builder()
                .addLoop("a", TickLoop.builder()
                        .tickPeriod(Duration.ofMillis(10))
                        .onTick(t -> {}))
                .addLoop("b", TickLoop.builder()
                        .tickPeriod(Duration.ofMillis(10))
                        .onTick(t -> {}))
                .build();

        TickQueue<String> bInbox = group.loop("b").createQueue("from-a", msg -> {
            received.incrementAndGet();
            done.countDown();
        });

        group.start();

        // From the "a" loop thread, send 5 messages to "b".
        group.loop("a").runOnMain(() -> {
            for (int i = 0; i < 5; i++) {
                bInbox.offer("msg-" + i);
            }
        });

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        group.stop();

        assertThat(received.get()).isEqualTo(5);
    }

    @Test
    @Timeout(5)
    void stop_stops_all_loops() throws Exception {
        TickGroup group = TickGroup.builder()
                .addLoop("a", TickLoop.builder()
                        .tickPeriod(Duration.ofMillis(20))
                        .onTick(t -> {}))
                .addLoop("b", TickLoop.builder()
                        .tickPeriod(Duration.ofMillis(20))
                        .onTick(t -> {}))
                .build();

        group.start();
        Thread.sleep(30);
        assertThat(group.isAllRunning()).isTrue();

        group.stop();
        assertThat(group.loops().values())
                .allSatisfy(l -> assertThat(l.isRunning()).isFalse());
    }

    @Test
    void empty_group_rejected_at_build() {
        assertThatThrownBy(() -> TickGroup.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one loop");
    }

    @Test
    void duplicate_name_rejected() {
        TickGroupBuilder b = TickGroup.builder()
                .addLoop("x", TickLoop.builder().tickPeriod(Duration.ofMillis(10)).onTick(t -> {}));
        assertThatThrownBy(() -> b.addLoop("x",
                TickLoop.builder().tickPeriod(Duration.ofMillis(20)).onTick(t -> {})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void unknown_loop_name_throws_with_helpful_message() {
        TickGroup g = TickGroup.builder()
                .addLoop("physics", TickLoop.builder().tickPeriod(Duration.ofMillis(10)).onTick(t -> {}))
                .addLoop("ai", TickLoop.builder().tickPeriod(Duration.ofMillis(50)).onTick(t -> {}))
                .build();
        assertThatThrownBy(() -> g.loop("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("physics")
                .hasMessageContaining("ai");
    }
}
