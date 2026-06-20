package me.prexorjustin.prexorcloud.controller.cluster.reload;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.events.ClusterConfigChangedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cluster-config live-reload fan-out. Bridges {@code cluster_config} writes onto the in-process
 * subsystems that hold a mutable copy of cluster-shared settings — CORS allow-list, rate limiter, JWT
 * signing keys — so an operator's {@code POST /cluster/config} (or a rollback) takes effect on the
 * serving leader without a restart.
 *
 * <p>Wiring: the cluster-config write path publishes a {@link ClusterConfigChangedEvent} on the local
 * bus after a successful write. This coordinator subscribes to that event, folds the active config (via
 * the supplied {@code effectiveConfig} reader, normally {@code MongoClusterPlane::effectiveConfig}), and
 * dispatches it to every registered {@link ClusterConfigSubscriber}. A subscriber that throws is logged
 * and isolated — one bad reload must not stop the others, nor wedge the event bus.
 */
public final class ClusterConfigReloadCoordinator implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClusterConfigReloadCoordinator.class);

    private final Supplier<Map<String, Object>> effectiveConfig;
    private final EventBus eventBus;
    private final List<ClusterConfigSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private volatile EventSubscription subscription;

    public ClusterConfigReloadCoordinator(Supplier<Map<String, Object>> effectiveConfig, EventBus eventBus) {
        this.effectiveConfig = effectiveConfig;
        this.eventBus = eventBus;
    }

    /** Register a subsystem to receive folded config on every commit (and once at {@link #start()}). */
    public ClusterConfigReloadCoordinator register(ClusterConfigSubscriber subscriber) {
        if (subscriber != null) {
            subscribers.add(subscriber);
        }
        return this;
    }

    /**
     * Subscribe to the event bus and prime every subscriber with the config that
     * is already active. Priming is what lets a freshly-joined controller adopt
     * the cluster-authoritative CORS / rate-limit / JWT values that arrived via
     * snapshot, rather than the stale ones it booted from {@code controller.yml}.
     * On a single-controller install the active config was seeded from that same
     * yaml, so priming is a no-op.
     */
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        subscription = eventBus.subscribe(ClusterConfigChangedEvent.class, this::onConfigChanged);
        dispatch("startup", 0);
    }

    private void onConfigChanged(ClusterConfigChangedEvent event) {
        dispatch(event.action(), event.version());
    }

    void dispatch(String trigger, int version) {
        Map<String, Object> effective;
        try {
            effective = effectiveConfig.get();
        } catch (RuntimeException e) {
            logger.error("cluster_config reload ({}) could not fold effective config: {}", trigger, e.getMessage(), e);
            return;
        }
        int applied = 0;
        for (ClusterConfigSubscriber subscriber : subscribers) {
            try {
                subscriber.onClusterConfig(effective);
                applied++;
            } catch (RuntimeException e) {
                logger.error(
                        "cluster_config subscriber '{}' failed on reload ({} v{}): {}",
                        subscriber.name(),
                        trigger,
                        version,
                        e.getMessage(),
                        e);
            }
        }
        logger.info(
                "cluster_config reload ({} v{}) dispatched to {}/{} subscriber(s)",
                trigger,
                version,
                applied,
                subscribers.size());
    }

    @Override
    public void close() {
        EventSubscription sub = subscription;
        if (sub != null) {
            sub.unsubscribe();
            subscription = null;
        }
    }
}
