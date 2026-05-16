package me.prexorjustin.prexorcloud.controller.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PendingRequestRegistryTest {

    private ScheduledExecutorService scheduler;
    private PendingRequestRegistry registry;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        registry = new PendingRequestRegistry(scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void completesPendingRequest() throws Exception {
        CompletableFuture<String> future = registry.register("req-1", "node-a", Duration.ofSeconds(5));
        registry.complete("req-1", "hello");
        assertEquals("hello", future.get(1, TimeUnit.SECONDS));
        assertEquals(0, registry.pendingCount());
    }

    @Test
    void timesOutExceptionally() {
        CompletableFuture<String> future = registry.register("req-2", "node-a", Duration.ofMillis(50));
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof TimeoutException, "expected TimeoutException, got " + ex.getCause());
    }

    @Test
    void unknownIdCompleteIsNoOp() {
        registry.complete("never-registered", "stray");
        assertEquals(0, registry.pendingCount());
    }

    @Test
    void failAllSelectiveByScope() {
        CompletableFuture<String> futA = registry.register("req-a", "node-a", Duration.ofSeconds(10));
        CompletableFuture<String> futB = registry.register("req-b", "node-b", Duration.ofSeconds(10));

        registry.failAll(e -> "node-a".equals(e.scope()), new IllegalStateException("disconnect"));

        assertTrue(futA.isCompletedExceptionally(), "node-a future must fail");
        assertEquals(false, futB.isDone(), "node-b future must remain pending");
        assertEquals(1, registry.pendingCount());
    }

    @Test
    void reusedRequestIdReplacesPriorAndFailsOldFuture() {
        CompletableFuture<String> first = registry.register("dup", "node-a", Duration.ofSeconds(10));
        CompletableFuture<String> second = registry.register("dup", "node-a", Duration.ofSeconds(10));

        assertTrue(first.isCompletedExceptionally(), "first future must be failed when id is reused");
        registry.complete("dup", "ok");
        assertEquals(false, second.isCompletedExceptionally(), "second future must complete normally");
    }
}
