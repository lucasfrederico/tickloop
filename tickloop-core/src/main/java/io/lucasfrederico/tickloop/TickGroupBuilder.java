package io.lucasfrederico.tickloop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for {@link TickGroup}. Use {@link TickGroup#builder()} to start.
 */
public final class TickGroupBuilder {

    private final Map<String, TickLoopBuilder> entries = new LinkedHashMap<>();

    TickGroupBuilder() {}

    /**
     * Register a named loop. The {@code threadName} of each loop is
     * derived as {@code "tickloop-" + name} unless the loop builder
     * already set it explicitly.
     *
     * @throws IllegalArgumentException if {@code name} is already registered
     */
    public TickGroupBuilder addLoop(String name, TickLoopBuilder loopBuilder) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(loopBuilder, "loopBuilder");
        if (entries.containsKey(name)) {
            throw new IllegalArgumentException("duplicate loop name: '" + name + "'");
        }
        entries.put(name, loopBuilder);
        return this;
    }

    public TickGroup build() {
        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "TickGroup must have at least one loop; call addLoop(...) before build()");
        }
        Map<String, TickLoop> loops = new LinkedHashMap<>();
        for (Map.Entry<String, TickLoopBuilder> e : entries.entrySet()) {
            TickLoopBuilder b = e.getValue();
            // If the user didn't override threadName on the loop builder, give
            // it a meaningful default derived from the group name.
            if ("tickloop-main".equals(b.threadName)) {
                b.threadName = "tickloop-" + e.getKey();
            }
            loops.put(e.getKey(), b.build());
        }
        return new TickGroup(loops);
    }
}
