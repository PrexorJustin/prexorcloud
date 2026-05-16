package me.prexorjustin.prexorcloud.controller.grpc;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic, thread-safe map of {@code requestId → CompletableFuture<T>} with
 * per-entry scheduled timeouts and selective {@code failAll} cleanup. First
 * async-correlation primitive in the codebase — keeps daemon round-trips like
 * {@code WalkInstanceFiles → InstanceFileTree} bounded.
 *
 * <p>
 * Generic over the reply payload type so different round-trips can share the
 * registry without unsafe casts. Each registered request stores a small
 * metadata record alongside its future so {@link #failAll(Predicate, Throwable)}
 * can scope cleanup (e.g. fail every pending request for a disconnected node).
 * </p>
 */
public final class PendingRequestRegistry {

    private static final Logger logger = LoggerFactory.getLogger(PendingRequestRegistry.class);

    /**
     * Per-entry metadata stamped at registration. Used by {@link #failAll(Predicate, Throwable)} to
     * decide which pending futures to fast-fail on (e.g. when their owning node disconnects).
     */
    public record Entry(String requestId, String scope, CompletableFuture<?> future, ScheduledFuture<?> timeoutTask) {}

    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, Entry> pending = new ConcurrentHashMap<>();

    public PendingRequestRegistry(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Register a new pending request.
     *
     * @param requestId unique correlation id
     * @param scope     opaque grouping tag (e.g. {@code nodeId}) used by {@link #failAll(Predicate, Throwable)}
     * @param timeout   how long to wait before completing the future exceptionally with a {@link TimeoutException}
     */
    public <T> CompletableFuture<T> register(String requestId, String scope, Duration timeout) {
        CompletableFuture<T> future = new CompletableFuture<>();
        ScheduledFuture<?> task = scheduler.schedule(
                () -> {
                    Entry entry = pending.remove(requestId);
                    if (entry != null) {
                        entry.future.completeExceptionally(new TimeoutException(
                                "request " + requestId + " timed out after " + timeout.toMillis() + "ms"));
                    }
                },
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        Entry entry = new Entry(requestId, scope == null ? "" : scope, future, task);
        Entry prior = pending.put(requestId, entry);
        if (prior != null) {
            prior.timeoutTask.cancel(false);
            prior.future.completeExceptionally(new IllegalStateException("requestId " + requestId + " reused"));
        }
        return future;
    }

    /**
     * Complete the future for {@code requestId} with {@code response}. No-op if the
     * id is unknown or already completed/expired.
     */
    @SuppressWarnings("unchecked")
    public <T> void complete(String requestId, T response) {
        Entry entry = pending.remove(requestId);
        if (entry == null) {
            logger.debug("PendingRequestRegistry.complete: unknown or expired requestId {}", requestId);
            return;
        }
        entry.timeoutTask.cancel(false);
        ((CompletableFuture<T>) entry.future).complete(response);
    }

    /** Fail every entry matching {@code predicate} with {@code cause}. */
    public void failAll(Predicate<Entry> predicate, Throwable cause) {
        // Snapshot so concurrent register/complete during failover-cleanup can't perturb iteration.
        for (Entry entry : List.copyOf(pending.values())) {
            if (predicate.test(entry)) {
                if (pending.remove(entry.requestId(), entry)) {
                    entry.timeoutTask.cancel(false);
                    entry.future.completeExceptionally(cause);
                }
            }
        }
    }

    /** Test-only / observability — count of currently outstanding requests. */
    public int pendingCount() {
        return pending.size();
    }
}
