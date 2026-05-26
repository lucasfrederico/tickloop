package io.lucasfrederico.tickloop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TickQueueTest {

    @Test
    @Timeout(5)
    void events_offered_from_other_threads_are_drained_on_loop_thread() throws Exception {
        AtomicReference<String> consumerThread = new AtomicReference<>();
        List<Integer> consumed = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(10);

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .threadName("queue-test-A")
                .onTick(tick -> {})
                .build();
        TickQueue<Integer> q = loop.createQueue("events", event -> {
            consumerThread.compareAndSet(null, Thread.currentThread().getName());
            consumed.add(event);
            done.countDown();
        });
        loop.start();

        // Offer 10 events from a different thread.
        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                q.offer(i);
            }
        }, "producer").start();

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        loop.stop();

        assertThat(consumerThread.get()).isEqualTo("queue-test-A");
        assertThat(consumed).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    @Timeout(10)
    void survives_concurrent_producers() throws Exception {
        // 4 producer threads each offer 1000 events. Verify all 4000 are
        // consumed on the loop thread with no losses.
        final int producers = 4;
        final int perProducer = 1000;
        AtomicInteger received = new AtomicInteger();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(2))
                .threadName("queue-test-B")
                .onTick(tick -> {})
                .build();
        TickQueue<Long> q = loop.createQueue("events", event -> received.incrementAndGet());
        loop.start();

        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            Thread t = new Thread(() -> {
                for (int i = 0; i < perProducer; i++) {
                    q.offer((long) i);
                }
            }, "producer-" + p);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();

        // Give the loop a few ticks to drain the queue.
        long deadline = System.currentTimeMillis() + 3000;
        while (received.get() < producers * perProducer && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        loop.stop();

        assertThat(received.get())
                .as("all offered events should reach the consumer")
                .isEqualTo(producers * perProducer);
    }

    @Test
    @Timeout(5)
    void multiple_queues_drain_in_registration_order() throws Exception {
        List<String> drainOrder = new CopyOnWriteArrayList<>();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(20))
                .onTick(tick -> {})
                .build();

        TickQueue<String> first = loop.createQueue("first", v -> drainOrder.add("first:" + v));
        TickQueue<String> second = loop.createQueue("second", v -> drainOrder.add("second:" + v));

        first.offer("a");
        second.offer("b");
        first.offer("c");
        second.offer("d");

        loop.start();
        Thread.sleep(60); // ~3 ticks
        loop.stop();

        // First queue is drained fully before second queue starts.
        assertThat(drainOrder).containsExactly("first:a", "first:c", "second:b", "second:d");
    }

    @Test
    @Timeout(5)
    void consumer_exception_does_not_kill_loop_or_block_other_queues() throws Exception {
        AtomicInteger thrown = new AtomicInteger();
        AtomicInteger goodConsumed = new AtomicInteger();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .onTick(tick -> {})
                .build();

        TickQueue<Integer> bad = loop.createQueue("bad", v -> {
            thrown.incrementAndGet();
            throw new RuntimeException("boom on " + v);
        });
        TickQueue<Integer> good = loop.createQueue("good", v -> goodConsumed.incrementAndGet());

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {});
        try {
            bad.offer(1);
            good.offer(100);

            loop.start();
            Thread.sleep(50);
            loop.stop();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(null);
        }

        // The bad queue exception is caught and reported, but the loop and
        // the good queue keep running.
        assertThat(thrown.get()).isEqualTo(1);
        assertThat(goodConsumed.get()).isEqualTo(1);
    }
}
