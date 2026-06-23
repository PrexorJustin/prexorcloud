package me.prexorjustin.prexorcloud.protocol.stream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.Consumer;

import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-writer, flow-controlled, bounded wrapper around a gRPC {@link CallStreamObserver}.
 *
 * <p><b>Why this exists.</b> A gRPC stream observer's {@code onNext} is <em>not</em> thread-safe, yet
 * both control streams are written from several threads — controller→daemon from placement/command
 * dispatch and from the inbound-handler virtual threads that serve template fetches / handshake acks /
 * pre-warm; daemon→controller from per-instance console capture, the heartbeat scheduler, status and
 * crash reporters. Concurrent {@code onNext} interleaves and corrupts the outbound frame
 * ("Failed to stream message" → {@code CANCELLED}), tearing the stream down — which on the controller
 * marks every co-located instance CRASHED. Routing <em>every</em> write through one of these makes a
 * concurrent {@code onNext} impossible by construction: the raw observer is private, so no caller can
 * bypass it (earlier per-call-site {@code synchronized} fixes kept getting bypassed by a new writer).
 *
 * <p><b>Backpressure.</b> Messages are handed to {@code onNext} only while the transport
 * {@link CallStreamObserver#isReady() isReady()}; otherwise they wait in a bounded queue drained by the
 * gRPC on-ready callback. This caps heap when the peer is slow instead of letting gRPC buffer without
 * limit. When the queue is full, {@link #offer} traffic (best-effort, e.g. console output) is dropped;
 * {@link #send} traffic (commands / status / acks) is never dropped — it evicts the oldest queued
 * best-effort message, or, if there is none, exceeds the soft cap with a warning.
 *
 * <p>Construct on the thread that owns the call (server: at {@code connect()} entry; client: inside
 * {@code ClientResponseObserver.beforeStart}) so {@code setOnReadyHandler} is registered before the
 * first write. Not reusable across reconnects — make a new writer per stream.
 */
public final class GuardedStreamWriter<T> implements StreamObserver<T> {

    private static final Logger logger = LoggerFactory.getLogger(GuardedStreamWriter.class);
    private static final long DROP_LOG_INTERVAL_MS = 5_000;

    private record Entry<T>(T message, boolean droppable) {}

    private final StreamObserver<T> observer;
    // Non-null only when the observer supports backpressure (a real gRPC Server/ClientCallStreamObserver).
    // A plain StreamObserver (tests, non-flow-controlled callers) is treated as always-ready.
    private final CallStreamObserver<T> flowControl;
    private final int capacity;
    private final String name;

    private final Object lock = new Object();
    private final Deque<Entry<T>> queue = new ArrayDeque<>();
    private boolean draining;
    private boolean terminated;
    private long dropped;
    private long lastDropLogMs;
    private volatile Consumer<Throwable> onSendFailure = t -> {};

    public GuardedStreamWriter(StreamObserver<T> observer, int capacity, String name) {
        this.observer = observer;
        this.flowControl = observer instanceof CallStreamObserver<T> cso ? cso : null;
        this.capacity = Math.max(1, capacity);
        this.name = name;
        // Resume draining whenever the transport frees up. Runs on a gRPC thread; it only ever calls
        // onNext through drain(), under the same lock as send/offer, so writes never interleave.
        if (flowControl != null) {
            flowControl.setOnReadyHandler(this::drain);
        }
    }

    private boolean transportReady() {
        return flowControl == null || flowControl.isReady();
    }

    /**
     * Register a one-shot callback invoked (outside the lock) the first time a write fails — the stream
     * is dead. The owner uses it to drive its existing disconnect/reconnect path.
     */
    public void onSendFailure(Consumer<Throwable> handler) {
        this.onSendFailure = handler == null ? t -> {} : handler;
    }

    /** Enqueue a message that must not be dropped (commands, status, acks, template data). */
    public void send(T message) {
        enqueue(message, false);
    }

    /** Enqueue best-effort traffic (console output) — dropped under sustained backpressure. */
    public void offer(T message) {
        enqueue(message, true);
    }

    private void enqueue(T message, boolean droppable) {
        synchronized (lock) {
            if (terminated) {
                return;
            }
            if (queue.size() >= capacity) {
                if (droppable) {
                    recordDrop();
                    return; // shed best-effort traffic under pressure
                }
                if (!evictOneDroppable()) {
                    // No best-effort traffic to shed — a burst of critical messages. Exceed the soft
                    // cap rather than lose a command; the queue drains as soon as the peer is ready.
                    logger.warn("{}: outbound queue over soft cap {} — forcing critical message", name, capacity);
                }
            }
            queue.addLast(new Entry<>(message, droppable));
        }
        drain();
    }

    private boolean evictOneDroppable() { // caller holds lock
        for (Iterator<Entry<T>> it = queue.iterator(); it.hasNext(); ) {
            if (it.next().droppable()) {
                it.remove();
                recordDrop();
                return true;
            }
        }
        return false;
    }

    private void drain() {
        Throwable failure = null;
        Consumer<Throwable> handler = null;
        synchronized (lock) {
            if (draining || terminated) {
                return; // a single drain loop owns the stream; reentrant on-ready calls just return
            }
            draining = true;
            try {
                while (!terminated && transportReady()) {
                    Entry<T> e = queue.pollFirst();
                    if (e == null) {
                        break;
                    }
                    try {
                        observer.onNext(e.message());
                    } catch (RuntimeException ex) {
                        // StatusRuntimeException (stream cancelled) OR IllegalStateException (call already
                        // half-closed) — both mean the stream is gone. Stop and let the owner reconnect.
                        terminated = true;
                        queue.clear();
                        failure = ex;
                        handler = onSendFailure;
                        break;
                    }
                }
            } finally {
                draining = false;
            }
        }
        if (handler != null) {
            handler.accept(failure); // outside the lock: the handler may tear down / reconnect
        }
    }

    /** Gracefully half-close the stream. */
    public void complete() {
        synchronized (lock) {
            if (terminated) {
                return;
            }
            terminated = true;
            queue.clear();
            try {
                observer.onCompleted();
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
    }

    /** Terminate the stream with an error (e.g. leadership loss → daemon reconnects). */
    public void error(Throwable t) {
        synchronized (lock) {
            if (terminated) {
                return;
            }
            terminated = true;
            queue.clear();
            try {
                observer.onError(t);
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
    }

    public boolean isTerminated() {
        synchronized (lock) {
            return terminated;
        }
    }

    public long droppedCount() {
        synchronized (lock) {
            return dropped;
        }
    }

    // --- StreamObserver facade ---------------------------------------------------------------------
    // Lets existing {@code observer.onNext(...)} call sites route through the guarded, serialized,
    // flow-controlled path with zero signature changes: pass the writer wherever the raw observer
    // flows today. {@code onNext} counts as a critical send (use {@link #offer} for droppable traffic).

    @Override
    public void onNext(T value) {
        send(value);
    }

    @Override
    public void onError(Throwable t) {
        error(t);
    }

    @Override
    public void onCompleted() {
        complete();
    }

    private void recordDrop() { // caller holds lock
        dropped++;
        long now = System.currentTimeMillis();
        if (now - lastDropLogMs > DROP_LOG_INTERVAL_MS) {
            logger.info("{}: dropped {} best-effort message(s) under backpressure (total {})", name, 1, dropped);
            lastDropLogMs = now;
        }
    }
}
