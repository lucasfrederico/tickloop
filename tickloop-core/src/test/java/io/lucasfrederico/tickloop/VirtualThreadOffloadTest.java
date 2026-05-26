package io.lucasfrederico.tickloop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualThreadOffloadTest {

    @Test
    @Timeout(5)
    void offload_runs_on_virtual_thread_when_enabled() throws Exception {
        AtomicReference<Boolean> isVirtual = new AtomicReference<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<TickLoop> loopRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .threadName("vt-test")
                .useVirtualThreadsForOffload(true)
                .onTick(tick -> {
                    if (tick == 0) {
                        loopRef.get().offload(() -> {
                            Thread current = Thread.currentThread();
                            isVirtual.set(current.isVirtual());
                            threadName.set(current.getName());
                            return null;
                        }).thenOnMain(v -> done.countDown());
                    }
                })
                .build();
        loopRef.set(loop);
        loop.start();

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        loop.stop();

        assertThat(isVirtual.get())
                .as("offload work should run on a virtual thread when useVirtualThreadsForOffload(true)")
                .isTrue();
        assertThat(threadName.get())
                .as("virtual thread name should follow the configured prefix")
                .startsWith("vt-test-offload-vt-");
    }

    @Test
    @Timeout(5)
    void offload_runs_on_platform_thread_by_default() throws Exception {
        AtomicReference<Boolean> isVirtual = new AtomicReference<>();
        AtomicReference<TickLoop> loopRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .onTick(tick -> {
                    if (tick == 0) {
                        loopRef.get().offload(() -> {
                            isVirtual.set(Thread.currentThread().isVirtual());
                            return null;
                        }).thenOnMain(v -> done.countDown());
                    }
                })
                .build();
        loopRef.set(loop);
        loop.start();

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        loop.stop();

        assertThat(isVirtual.get())
                .as("offload work should run on platform thread by default")
                .isFalse();
    }
}
