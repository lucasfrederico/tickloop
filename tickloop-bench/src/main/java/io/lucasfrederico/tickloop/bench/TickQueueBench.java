package io.lucasfrederico.tickloop.bench;

import io.lucasfrederico.tickloop.BackpressureMode;
import io.lucasfrederico.tickloop.TickLoop;
import io.lucasfrederico.tickloop.TickQueue;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throughput of {@link TickQueue#offer} under different concurrency profiles.
 *
 * <p>Run with:
 * <pre>{@code
 * mvn -pl tickloop-bench package
 * java -jar tickloop-bench/target/tickloop-bench.jar TickQueueBench
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
public class TickQueueBench {

    private TickLoop loop;
    private TickQueue<Long> unboundedQueue;
    private TickQueue<Long> boundedDropOldest;
    private AtomicLong payload;

    @Setup(Level.Trial)
    public void setup() throws InterruptedException {
        payload = new AtomicLong();
        loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .onTick(t -> {})
                .build();
        unboundedQueue = loop.createQueue("bench-unbounded", v -> {});
        boundedDropOldest = loop.createBoundedQueue(
                "bench-bounded", 4096, BackpressureMode.DROP_OLDEST, v -> {});
        loop.start();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        loop.stop();
    }

    /** Unbounded queue, 1 producer thread. Pure offer throughput. */
    @Benchmark
    @Group("unbounded_1p")
    @GroupThreads(1)
    public boolean unbounded_single_producer() {
        return unboundedQueue.offer(payload.incrementAndGet());
    }

    /** Unbounded queue, 4 producer threads contending on offer. */
    @Benchmark
    @Group("unbounded_4p")
    @GroupThreads(4)
    public boolean unbounded_four_producers() {
        return unboundedQueue.offer(payload.incrementAndGet());
    }

    /** Bounded DROP_OLDEST, 1 producer. Same workload, observes drop overhead. */
    @Benchmark
    @Group("bounded_drop_oldest_1p")
    @GroupThreads(1)
    public boolean bounded_drop_oldest_single_producer() {
        return boundedDropOldest.offer(payload.incrementAndGet());
    }
}
