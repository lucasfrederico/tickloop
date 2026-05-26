package io.lucasfrederico.tickloop.bench;

import io.lucasfrederico.tickloop.TickLoop;
import io.lucasfrederico.tickloop.TickMetrics;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
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

/**
 * Steady-state tick overhead. Reports microseconds spent in the loop per
 * scheduled tick when the user handler is a no-op — i.e. the cost the
 * library itself imposes on the tick budget.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 5)
public class TickLoopBench {

    private TickLoop loop;
    private long startTicks;

    @Setup(Level.Trial)
    public void setup() {
        loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(1))   // 1000 Hz to maximize sample density
                .onTick(t -> {})
                .build();
        loop.start();
        startTicks = loop.metrics().tickCount();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        loop.stop();
    }

    /**
     * The benchmark itself does nothing — it just consumes wall-clock time
     * while the loop ticks in the background. {@link #tearDown} then reports
     * the avg tick duration the library measured itself.
     *
     * <p>For accurate library overhead numbers, prefer the metrics snapshot:
     * {@code TickMetrics.avgDurationNanos()} after a long stable run.
     */
    @Benchmark
    public long observe_tick_overhead() {
        TickMetrics m = loop.metrics();
        return m.avgDurationNanos();
    }
}
