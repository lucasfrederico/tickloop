package io.lucasfrederico.tickloop.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound from {@code tickloop.*} keys in {@code application.yml} /
 * {@code application.properties}.
 *
 * <p>Example:
 * <pre>{@code
 * tickloop:
 *   enabled: true
 *   tick-period: 50ms
 *   slow-tick-threshold: 40ms
 *   thread-name: my-loop
 *   use-virtual-threads-for-offload: false
 *   auto-start: true
 * }</pre>
 */
@ConfigurationProperties(prefix = "tickloop")
public class TickLoopProperties {

    /** Set to false to skip auto-configuration entirely. Default: true. */
    private boolean enabled = true;

    /** Tick period. Default: 50ms (= 20 Hz). */
    private Duration tickPeriod = Duration.ofMillis(50);

    /** If a tick's user work exceeds this duration, the slow-tick listener fires.
     * Default: 80% of {@code tickPeriod}. */
    private Duration slowTickThreshold;

    /** Name of the loop thread. Default: "tickloop-main". */
    private String threadName = "tickloop-main";

    /** Use virtual threads (Java 21+) for the offload pool. Default: false. */
    private boolean useVirtualThreadsForOffload = false;

    /** Start the loop automatically when the application context is ready.
     * Default: true. Set false to take manual control. */
    private boolean autoStart = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getTickPeriod() { return tickPeriod; }
    public void setTickPeriod(Duration tickPeriod) { this.tickPeriod = tickPeriod; }

    public Duration getSlowTickThreshold() { return slowTickThreshold; }
    public void setSlowTickThreshold(Duration slowTickThreshold) { this.slowTickThreshold = slowTickThreshold; }

    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public boolean isUseVirtualThreadsForOffload() { return useVirtualThreadsForOffload; }
    public void setUseVirtualThreadsForOffload(boolean v) { this.useVirtualThreadsForOffload = v; }

    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }
}
