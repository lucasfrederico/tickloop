package io.lucasfrederico.tickloop;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for invariants that must hold across many random
 * inputs. Tagged {@code property} so they can be filtered separately in CI.
 */
@Tag("property")
class TickLoopProperties {

    /**
     * Invariant: regardless of how many runOnMain submissions arrive
     * before {@code start}, the first user tick observes ALL of them
     * already executed.
     */
    @Property(tries = 50)
    void run_on_main_queued_before_start_drains_on_first_tick(
            @ForAll @IntRange(min = 0, max = 100) int submissions) throws Exception {

        AtomicInteger counter = new AtomicInteger();
        AtomicReference<Integer> observedOnFirstTick = new AtomicReference<>();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(20))
                .onTick(tick -> {
                    if (tick == 0) {
                        observedOnFirstTick.compareAndSet(null, counter.get());
                    }
                })
                .build();

        for (int i = 0; i < submissions; i++) {
            loop.runOnMain(counter::incrementAndGet);
        }
        loop.start();
        Thread.sleep(60);
        loop.stop();

        assertThat(observedOnFirstTick.get())
                .as("first tick should observe all %s pre-start submissions drained", submissions)
                .isEqualTo(submissions);
    }

    /**
     * Invariant: every event offered to a TickQueue (from any thread)
     * eventually reaches the consumer exactly once.
     */
    @Property(tries = 20)
    void all_offered_events_reach_consumer_exactly_once(
            @ForAll @IntRange(min = 1, max = 200) int eventCount,
            @ForAll @LongRange(min = 5, max = 30) long tickPeriodMs) throws Exception {

        AtomicInteger received = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(eventCount);

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(tickPeriodMs))
                .onTick(tick -> {})
                .build();
        TickQueue<Integer> q = loop.createQueue("events", v -> {
            received.incrementAndGet();
            done.countDown();
        });
        loop.start();

        for (int i = 0; i < eventCount; i++) {
            q.offer(i);
        }

        assertThat(done.await(3, TimeUnit.SECONDS))
                .as("should receive all %s events within 3s", eventCount)
                .isTrue();
        loop.stop();

        assertThat(received.get()).isEqualTo(eventCount);
    }

    /**
     * Invariant: tick numbers are monotonic from 0 and never skip.
     */
    @Property(tries = 20)
    void tick_numbers_are_monotonic_and_dense(
            @ForAll @LongRange(min = 5, max = 30) long tickPeriodMs,
            @ForAll @IntRange(min = 50, max = 200) int runForMs) throws Exception {

        AtomicInteger expectedNext = new AtomicInteger();
        AtomicReference<String> violation = new AtomicReference<>();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(tickPeriodMs))
                .onTick(tick -> {
                    int expected = expectedNext.getAndIncrement();
                    if (tick != expected) {
                        violation.compareAndSet(null,
                                "expected " + expected + " got " + tick);
                    }
                })
                .build();
        loop.start();
        Thread.sleep(runForMs);
        loop.stop();

        assertThat(violation.get()).isNull();
    }
}
