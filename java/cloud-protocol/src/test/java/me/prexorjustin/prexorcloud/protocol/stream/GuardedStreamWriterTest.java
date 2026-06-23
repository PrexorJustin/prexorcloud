package me.prexorjustin.prexorcloud.protocol.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.stub.CallStreamObserver;
import org.junit.jupiter.api.Test;

class GuardedStreamWriterTest {

    /** Controllable fake transport: drives isReady(), records deliveries, detects concurrent onNext. */
    private static final class FakeObserver extends CallStreamObserver<String> {
        volatile boolean ready = true;
        volatile boolean throwOnNext = false;
        Runnable onReady;
        final List<String> delivered = new ArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicBoolean sawConcurrent = new AtomicBoolean(false);

        @Override
        public void onNext(String value) {
            if (throwOnNext) {
                throw new IllegalStateException("call already half-closed");
            }
            if (inFlight.incrementAndGet() != 1) {
                sawConcurrent.set(true);
            }
            try {
                Thread.sleep(0, 50_000); // widen the window so an unserialized onNext is caught
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (inFlight.get() != 1) {
                sawConcurrent.set(true);
            }
            synchronized (delivered) {
                delivered.add(value);
            }
            inFlight.decrementAndGet();
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void setOnReadyHandler(Runnable onReadyHandler) {
            this.onReady = onReadyHandler;
        }

        @Override
        public void disableAutoInboundFlowControl() {}

        @Override
        public void request(int count) {}

        @Override
        public void setMessageCompression(boolean enable) {}

        List<String> deliveredSnapshot() {
            synchronized (delivered) {
                return List.copyOf(delivered);
            }
        }
    }

    @Test
    void concurrentSendsAreSerializedAndAllDelivered() throws Exception {
        var obs = new FakeObserver();
        var writer = new GuardedStreamWriter<>(obs, 10_000, "test");

        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        writer.send("m" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "senders finished");
        pool.shutdownNow();

        assertFalse(obs.sawConcurrent.get(), "onNext must never run concurrently");
        assertEquals(threads * perThread, obs.deliveredSnapshot().size(), "every critical message delivered");
    }

    @Test
    void doesNotWriteWhileNotReadyThenDrainsOnReady() {
        var obs = new FakeObserver();
        obs.ready = false;
        var writer = new GuardedStreamWriter<>(obs, 100, "test");

        writer.send("a");
        writer.send("b");
        assertTrue(obs.deliveredSnapshot().isEmpty(), "nothing is written while the transport is not ready");

        obs.ready = true;
        obs.onReady.run(); // gRPC fires this when the transport frees up
        assertEquals(List.of("a", "b"), obs.deliveredSnapshot(), "queued messages drain in order once ready");
    }

    @Test
    void dropsBestEffortWhenFullButNeverDropsCritical() {
        var obs = new FakeObserver();
        obs.ready = false; // hold everything in the queue so the cap is exercised
        var writer = new GuardedStreamWriter<>(obs, 3, "test");

        writer.offer("c1");
        writer.offer("c2");
        writer.offer("c3"); // queue full [c1,c2,c3]
        writer.offer("c4"); // full + droppable -> dropped
        writer.offer("c5"); // dropped
        assertEquals(2, writer.droppedCount());

        writer.send("CMD"); // full + critical -> evict oldest droppable (c1), enqueue CMD
        assertEquals(3, writer.droppedCount());

        obs.ready = true;
        obs.onReady.run();
        List<String> got = obs.deliveredSnapshot();
        assertTrue(got.contains("CMD"), "a critical message is never dropped");
        assertEquals(List.of("c2", "c3", "CMD"), got, "oldest best-effort evicted, order preserved");
    }

    @Test
    void terminatesAndReportsFailureWhenOnNextThrows() {
        var obs = new FakeObserver();
        obs.throwOnNext = true;
        var writer = new GuardedStreamWriter<>(obs, 100, "test");
        var failure = new AtomicReference<Throwable>();
        var calls = new AtomicInteger();
        writer.onSendFailure(t -> {
            failure.set(t);
            calls.incrementAndGet();
        });

        writer.send("x");
        assertTrue(writer.isTerminated(), "writer terminates after a failed write");
        assertEquals(1, calls.get(), "failure handler fires exactly once");
        assertTrue(failure.get() instanceof IllegalStateException, "broadened catch covers half-closed (IllegalStateException)");

        writer.send("y"); // no-op after termination, must not re-fire the handler
        assertEquals(1, calls.get());
    }

    @Test
    void plainStreamObserverIsSerializeOnlyAndAlwaysReady() throws Exception {
        // A non-CallStreamObserver (e.g. a test mock, or any caller that doesn't expose backpressure)
        // has no isReady()/setOnReadyHandler — the writer must degrade to serialize-only, always ready.
        var delivered = java.util.Collections.synchronizedList(new ArrayList<String>());
        var inFlight = new AtomicInteger();
        var sawConcurrent = new AtomicBoolean(false);
        io.grpc.stub.StreamObserver<String> plain = new io.grpc.stub.StreamObserver<>() {
            @Override
            public void onNext(String value) {
                if (inFlight.incrementAndGet() != 1) {
                    sawConcurrent.set(true);
                }
                try {
                    Thread.sleep(0, 50_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                delivered.add(value);
                inFlight.decrementAndGet();
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {}
        };
        var writer = new GuardedStreamWriter<>(plain, 100, "plain");

        int threads = 6;
        int perThread = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        writer.onNext("m" + i); // exercise the StreamObserver facade -> send
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "senders finished");
        pool.shutdownNow();

        assertFalse(sawConcurrent.get(), "facade onNext is serialized even for a plain observer");
        assertEquals(threads * perThread, delivered.size(), "every message delivered with no flow control");
    }

    @Test
    void completeIsIdempotentAndStopsDelivery() {
        var obs = new FakeObserver();
        var writer = new GuardedStreamWriter<>(obs, 100, "test");
        writer.send("a");
        writer.complete();
        writer.complete(); // idempotent
        writer.send("b"); // dropped after completion
        assertSame(true, writer.isTerminated());
        assertEquals(List.of("a"), obs.deliveredSnapshot());
    }
}
