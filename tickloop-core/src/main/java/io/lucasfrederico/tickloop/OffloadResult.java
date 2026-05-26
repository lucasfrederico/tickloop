package io.lucasfrederico.tickloop;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handle to a value being computed off the loop thread.
 *
 * <p>Returned by {@link TickLoop#offload}. Use {@link #thenOnMain} to
 * schedule a continuation that will run back on the loop thread when
 * the async work completes.
 *
 * <p>This is intentionally a thin wrapper, not a full {@link CompletableFuture}
 * surface — exposing all of {@code CompletionStage} would let callers
 * accidentally hop threads via {@code thenApply} on the async pool. The
 * only way back to the loop thread is {@code thenOnMain}.
 */
public final class OffloadResult<T> {

    private final CompletableFuture<T> future;
    private final TickLoop loop;

    OffloadResult(CompletableFuture<T> future, TickLoop loop) {
        this.future = future;
        this.loop = loop;
    }

    /**
     * Schedule {@code onSuccess} to run on the loop thread once the async
     * work completes successfully. If the work failed, the exception is
     * routed to the loop thread's uncaught exception handler.
     */
    public void thenOnMain(Consumer<T> onSuccess) {
        Objects.requireNonNull(onSuccess, "onSuccess");
        future.whenComplete((value, error) -> {
            if (error != null) {
                loop.runOnMain(() -> {
                    throw new RuntimeException(
                            "offload work failed and no error handler was set", error);
                });
            } else {
                loop.runOnMain(() -> onSuccess.accept(value));
            }
        });
    }

    /**
     * Schedule continuations for both success and failure. Both run on the
     * loop thread. Useful when the failure case is recoverable (e.g. retry
     * or fall back to cached value).
     */
    public void thenOnMain(Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onFailure, "onFailure");
        future.whenComplete((value, error) -> {
            if (error != null) {
                loop.runOnMain(() -> onFailure.accept(error));
            } else {
                loop.runOnMain(() -> onSuccess.accept(value));
            }
        });
    }

    /** True iff the underlying work has completed (success or failure). */
    public boolean isDone() {
        return future.isDone();
    }

    /**
     * Attempt to cancel the offload. If the work has already started in
     * the offload pool, cancellation is best-effort.
     */
    public boolean cancel() {
        return future.cancel(true);
    }
}
