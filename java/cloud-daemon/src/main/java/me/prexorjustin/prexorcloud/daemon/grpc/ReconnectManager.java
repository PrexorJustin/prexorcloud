package me.prexorjustin.prexorcloud.daemon.grpc;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import me.prexorjustin.prexorcloud.daemon.config.ReconnectConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event-driven reconnect manager. Instead of polling, reconnection is triggered
 * by the gRPC client's onError/onCompleted callbacks via
 * {@link #scheduleReconnect()}. Uses exponential backoff with jitter.
 */
public final class ReconnectManager {

    private static final Logger logger = LoggerFactory.getLogger(ReconnectManager.class);

    private final DaemonGrpcClient client;
    private final ReconnectConfig config;
    private final ScheduledExecutorService scheduler;

    private long currentDelay;
    private volatile boolean stopped = false;
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Runnable> reconnectListeners = new CopyOnWriteArrayList<>();

    public ReconnectManager(DaemonGrpcClient client, ReconnectConfig config) {
        this.client = client;
        this.config = config;
        this.currentDelay = config.initialDelayMs();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reconnect-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Called by the gRPC client when the stream disconnects (onError/onCompleted).
     * Schedules a reconnection attempt after the current backoff delay.
     */
    public void scheduleReconnect() {
        if (stopped) return;
        // Why: sustained-failure paths (onError → scheduleReconnect, plus the catch-block re-schedule
        // inside the scheduled task) could otherwise stack pending tasks in the executor queue.
        if (!reconnectScheduled.compareAndSet(false, true)) return;

        long delay = currentDelay;
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, delay / 4));
        long effectiveDelay = delay + jitter;

        logger.debug("Scheduling reconnection in {}ms", effectiveDelay);

        scheduler.schedule(
                () -> {
                    reconnectScheduled.set(false);
                    if (stopped) return;
                    try {
                        logger.info("Attempting reconnection...");
                        client.connect();
                        // If connect() succeeds without throwing, the handshake was sent.
                        // The backoff resets when the HandshakeAck arrives (via onConnected()).
                        // If the stream errors, scheduleReconnect() will be called again.
                    } catch (Exception e) {
                        logger.warn("Reconnection failed: {}", e.getMessage());
                        scheduleReconnect();
                    }
                },
                effectiveDelay,
                TimeUnit.MILLISECONDS);

        // Advance backoff for next attempt
        currentDelay = Math.min((long) (delay * config.multiplier()), config.maxDelayMs());
    }

    /**
     * Called when the connection is fully established (HandshakeAck received).
     * Resets the backoff delay and fires every registered reconnect listener so
     * subscribe-registration consumers (notably {@code DaemonEventBus}) can rebuild
     * their controller-side subscription state on every reconnect.
     */
    public void onConnected() {
        currentDelay = config.initialDelayMs();
        for (Runnable listener : reconnectListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                logger.warn("reconnect listener threw: {}", e.getMessage());
            }
        }
    }

    /**
     * Register a callback fired every time the daemon completes a successful handshake
     * (initial connect and every reconnect). Used by {@code DaemonEventBus} to re-register
     * the full set of currently-subscribed event types so the controller's per-daemon
     * subscription map is rebuilt without dropping events on the floor after a reconnect.
     */
    public void addReconnectListener(Runnable listener) {
        if (listener != null) {
            reconnectListeners.add(listener);
        }
    }

    public void stop() {
        stopped = true;
        scheduler.shutdownNow();
    }
}
