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
import me.prexorjustin.prexorcloud.protocol.stream.GuardedStreamWriter;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

/**
 * The daemon's outbound senders — console capture (a per-instance virtual thread), heartbeat/pong,
 * instance-status, crash reports — must all funnel through the single {@link GuardedStreamWriter} so a
 * concurrent {@code onNext} (which corrupts the frame → CANCELLED → the controller crashes every
 * co-located instance) is impossible. This verifies the client routes through the writer rather than
 * touching the raw stream; the writer's own serialization/flow-control is covered in cloud-protocol.
 */
final class DaemonGrpcClientSendSerializationTest {

    @Test
    void allSenderPathsRouteThroughTheGuardedWriterWithoutInterleaving() throws Exception {
        var client = new DaemonGrpcClient("ctrl-a", 9090, "node-1", "", 1024, Map.of(), null, null, null);

        var sawConcurrent = new AtomicBoolean(false);
        var inFlight = new AtomicInteger();
        var delivered = new AtomicInteger();
        StreamObserver<DaemonMessage> detector = new StreamObserver<>() {
            @Override
            public void onNext(DaemonMessage value) {
                if (inFlight.incrementAndGet() != 1) {
                    sawConcurrent.set(true);
                }
                try {
                    Thread.sleep(0, 50_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (inFlight.get() != 1) {
                    sawConcurrent.set(true);
                }
                delivered.incrementAndGet();
                inFlight.decrementAndGet();
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {}
        };

        // Put the client into the CONNECTED steady state with a writer wrapping our detector.
        setField(client, "writer", new GuardedStreamWriter<>(detector, 100_000, "test"));
        setField(client, "state", DaemonGrpcClient.State.CONNECTED);

        int threads = 8;
        int perThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // Mix the droppable console path and the critical pong/message path, like a
                        // running node: console capture + heartbeat firing from different threads.
                        if ((id + i) % 2 == 0) {
                            client.sendConsoleOutput("i-1", "line " + i);
                        } else {
                            client.sendPong(i);
                        }
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

        assertFalse(sawConcurrent.get(), "onNext must never run concurrently on the outbound stream");
        assertEquals(threads * perThread, delivered.get(), "every message routed through the writer reached the stream");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = DaemonGrpcClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
