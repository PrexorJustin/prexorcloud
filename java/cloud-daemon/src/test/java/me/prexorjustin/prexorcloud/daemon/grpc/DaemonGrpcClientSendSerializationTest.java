package me.prexorjustin.prexorcloud.daemon.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import me.prexorjustin.prexorcloud.protocol.DaemonMessage;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

/**
 * The daemon's outbound gRPC stream is a single chokepoint shared by console-capture (a per-instance
 * virtual thread), heartbeat/pong, instance-status, and crash-report threads. A gRPC
 * {@link StreamObserver} is not thread-safe for concurrent {@code onNext}: interleaved calls corrupt
 * the outbound frame ("Failed to stream message" -> CANCELLED), which tears down the control stream
 * and makes the controller mark every co-located instance CRASHED. {@code trySend} must serialize
 * {@code onNext} per stream (the daemon-side mirror of the controller's {@code NodeSession.send} fix).
 */
final class DaemonGrpcClientSendSerializationTest {

    @Test
    void concurrentSendsDoNotInterleaveOnNext() throws Exception {
        var client = new DaemonGrpcClient("ctrl-a", 9090, "node-1", "", 1024, Map.of(), null, null, null);

        var sawConcurrentEntry = new AtomicBoolean(false);
        var inFlight = new AtomicInteger(0);
        var delivered = new AtomicInteger(0);
        StreamObserver<DaemonMessage> detector = new StreamObserver<>() {
            @Override
            public void onNext(DaemonMessage value) {
                if (inFlight.incrementAndGet() != 1) {
                    sawConcurrentEntry.set(true);
                }
                // Widen the race window so an unsynchronized onNext is reliably caught.
                try {
                    Thread.sleep(0, 50_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (inFlight.get() != 1) {
                    sawConcurrentEntry.set(true);
                }
                delivered.incrementAndGet();
                inFlight.decrementAndGet();
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {}
        };

        // Put the client into the steady-state CONNECTED path with our detector as the live stream.
        setField(client, "requestStream", detector);
        setField(client, "state", DaemonGrpcClient.State.CONNECTED);

        int threads = 8;
        int perThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        client.sendConsoleOutput("i-1", "line " + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "all sender threads finished");
        pool.shutdownNow();

        assertFalse(sawConcurrentEntry.get(), "onNext must never run concurrently on a single stream");
        assertEquals(threads * perThread, delivered.get(), "every message reached the stream exactly once");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = DaemonGrpcClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
