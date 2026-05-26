package io.lucasfrederico.tickloop.spring;

import io.lucasfrederico.tickloop.SlowTickListener;
import io.lucasfrederico.tickloop.TickHandler;
import io.lucasfrederico.tickloop.TickLoop;
import io.lucasfrederico.tickloop.TickLoopBuilder;
import io.lucasfrederico.tickloop.TickMetrics;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.time.Duration;

/**
 * Auto-configures a {@link TickLoop} bean from a user-provided
 * {@link TickHandler} bean.
 *
 * <p>Activation rules:
 * <ul>
 *   <li>{@code tickloop.enabled=true} (default).</li>
 *   <li>{@link TickHandler} class is on the classpath (it is — that's
 *       the {@code tickloop-core} dep).</li>
 *   <li>A {@link TickHandler} bean is defined in the application context.</li>
 * </ul>
 *
 * <p>If the user defines their own {@link TickLoop} bean, this auto-config
 * stays out of the way ({@code @ConditionalOnMissingBean}).
 */
@AutoConfiguration
@ConditionalOnClass(TickLoop.class)
@ConditionalOnProperty(prefix = "tickloop", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TickLoopProperties.class)
public class TickLoopAutoConfiguration implements DisposableBean {

    private final TickLoopProperties properties;
    private TickLoop loop;

    public TickLoopAutoConfiguration(TickLoopProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(TickHandler.class)
    public TickLoop tickLoop(
            TickHandler handler,
            ObjectProvider<SlowTickListener> slowTickListener
    ) {
        TickLoopBuilder b = TickLoop.builder()
                .tickPeriod(properties.getTickPeriod())
                .threadName(properties.getThreadName())
                .useVirtualThreadsForOffload(properties.isUseVirtualThreadsForOffload())
                .onTick(handler);

        Duration explicitThreshold = properties.getSlowTickThreshold();
        if (explicitThreshold != null) {
            b.slowTickThreshold(explicitThreshold);
        }
        slowTickListener.ifAvailable(b::onSlowTick);

        this.loop = b.build();
        return this.loop;
    }

    /** Convenience bean: lets controllers/services {@code @Autowire} metrics directly. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(TickLoop.class)
    public TickMetrics tickMetrics(TickLoop loop) {
        return loop.metrics();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startLoopOnReady() {
        if (loop != null && properties.isAutoStart() && !loop.isRunning()) {
            loop.start();
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        if (loop != null && loop.isRunning()) {
            loop.stop();
        }
    }
}
