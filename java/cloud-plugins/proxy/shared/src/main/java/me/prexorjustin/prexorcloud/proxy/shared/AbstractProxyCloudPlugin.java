package me.prexorjustin.prexorcloud.proxy.shared;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.api.domain.InstanceView;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;
import me.prexorjustin.prexorcloud.plugin.common.dto.PendingTransferDto;

/**
 * Shared base for PrexorCloud proxy plugins (Velocity + BungeeCord).
 *
 * <p>Owns the controller-client lifecycle, state-cache wiring, backend-server
 * sync, transfer/message polling and proxy-metrics reporting. Concrete
 * platforms only have to implement the small set of platform-API hooks below.
 */
public abstract class AbstractProxyCloudPlugin {

    protected ProxyControllerClient controllerClient;
    protected CloudStateCache stateCache;

    // instanceId -> currently-registered "addr:port", so we can detect a backend that kept its id
    // but moved node/port and re-point the proxy at it (the proxy APIs won't update in place).
    private final Map<String, String> registeredBackends = new ConcurrentHashMap<>();

    /**
     * Initialises controller connection and state cache. Returns {@code true}
     * once initialisation completed successfully (or {@code false} if the
     * plugin runs in standalone mode because {@code CLOUD_INSTANCE_ID} is not
     * set).
     */
    protected final boolean initialize() {
        if (!PluginEnv.isCloudManaged()) {
            warn("PrexorCloud: CLOUD_INSTANCE_ID not set -- running in standalone mode");
            return false;
        }

        controllerClient =
                new ProxyControllerClient(PluginEnv.controllerUrl(), PluginEnv.pluginToken(), PluginEnv.instanceId());
        controllerClient.reportReady();
        stateCache = new CloudStateCache(controllerClient, 5);

        stateCache.addListener((current, added, removed, becameRunning) -> {
            for (String instanceId : removed) {
                unregisterBackend(instanceId);
                registeredBackends.remove(instanceId);
            }
            // Reconcile every RUNNING backend against its CURRENT node:port rather than only
            // reacting to added/becameRunning. An instance that keeps its id but moves node:port
            // (drain-migrate, crash-heal, affinity reschedule) stays "present" in the cache, so a
            // diff-only sync never refreshes its registration and the proxy keeps dialing the old
            // address ("No servers available"). Velocity/Bungee can't update a registered server's
            // address in place, so on a move we unregister the stale entry before re-registering.
            for (InstanceView instance : current) {
                String instanceId = instance.instanceId();
                if (instanceId.equals(PluginEnv.instanceId())) continue;
                if (instance.state() != InstanceState.RUNNING) continue;
                String endpoint = instance.nodeAddress() + ":" + instance.port();
                if (endpoint.equals(registeredBackends.get(instanceId))) continue;
                if (registeredBackends.containsKey(instanceId)) {
                    unregisterBackend(instanceId);
                }
                registerBackend(instanceId, instance.nodeAddress(), instance.port());
                registeredBackends.put(instanceId, endpoint);
            }
        });

        stateCache.start();

        onAfterCacheStarted();

        scheduleRepeating("transfer-poller", 2L, 2L, TimeUnit.SECONDS, this::pollPendingTransfers);
        scheduleRepeating("metrics", 30L, 30L, TimeUnit.SECONDS, this::reportProxyMetrics);
        if (supportsCrossProxyMessages()) {
            scheduleRepeating("messages", 1L, 1L, TimeUnit.SECONDS, this::pollPendingMessages);
        }

        info("PrexorCloud initialized (instanceId=" + PluginEnv.instanceId() + ", group=" + PluginEnv.group() + ")");
        return true;
    }

    /** Cancels schedules and tears down the cache. */
    protected final void shutdown() {
        cancelTasks();
        if (stateCache != null) {
            stateCache.stop();
        }
        info("PrexorCloud disconnected");
    }

    private void pollPendingTransfers() {
        if (controllerClient == null) return;
        try {
            for (PendingTransferDto transfer : controllerClient.fetchPendingTransfers()) {
                UUID playerUuid;
                try {
                    playerUuid = UUID.fromString(transfer.playerUuid());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (transferPlayer(playerUuid, transfer)) {
                    controllerClient.ackTransfer(playerUuid);
                }
            }
        } catch (Exception e) {
            warn("Transfer poll failed: " + e.getMessage());
        }
    }

    private void pollPendingMessages() {
        if (controllerClient == null) return;
        try {
            for (var msg : controllerClient.fetchPendingMessages()) {
                UUID toUuid;
                try {
                    toUuid = UUID.fromString(msg.toUuid());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (deliverMessage(toUuid, msg.fromName(), msg.content())) {
                    controllerClient.ackMessage(msg.id());
                }
            }
        } catch (Exception e) {
            warn("Message poll failed: " + e.getMessage());
        }
    }

    private void reportProxyMetrics() {
        if (controllerClient == null) return;
        try {
            controllerClient.reportProxyMetrics(currentPlayerCount(), collectPings());
        } catch (Exception e) {
            warn("Proxy metrics report failed: " + e.getMessage());
        }
    }

    // === Platform hooks ===

    /** Register a backend server with the proxy's server map. */
    protected abstract void registerBackend(String instanceId, String address, int port);

    /** Remove a backend server from the proxy's server map. */
    protected abstract void unregisterBackend(String instanceId);

    /**
     * Transfer the given player to the requested target. Implementations should
     * register the target server with the proxy if it isn't already known.
     *
     * @return {@code true} if the transfer was issued (and should be ACKed),
     *         {@code false} if the player isn't online on this proxy.
     */
    protected abstract boolean transferPlayer(UUID playerUuid, PendingTransferDto transfer);

    /** Total online player count on this proxy, used for metrics. */
    protected abstract int currentPlayerCount();

    /** Per-player ping samples for metrics, including username for display. */
    protected abstract List<PlayerPingSample> collectPings();

    /**
     * Schedule a recurring task with the platform's scheduler. Implementations
     * are responsible for tracking the handle so {@link #cancelTasks()} can
     * cancel it during shutdown.
     */
    protected abstract void scheduleRepeating(
            String name, long initialDelay, long period, TimeUnit unit, Runnable task);

    /** Cancel all tasks scheduled via {@link #scheduleRepeating}. */
    protected abstract void cancelTasks();

    /**
     * Whether this proxy supports cross-proxy player messaging
     * (i.e. {@link #deliverMessage} is implemented). Default: {@code false}.
     */
    protected boolean supportsCrossProxyMessages() {
        return false;
    }

    /**
     * Deliver a pending cross-proxy message to a player. Default: no-op
     * returning {@code false}. Override and return {@code true} once delivered.
     */
    protected boolean deliverMessage(UUID toUuid, String fromName, String content) {
        return false;
    }

    /**
     * Hook invoked after the state cache has started. Concrete plugins use
     * this to register player listeners and start their {@code CloudApi}.
     */
    protected void onAfterCacheStarted() {}

    protected abstract void info(String message);

    protected abstract void warn(String message);
}
