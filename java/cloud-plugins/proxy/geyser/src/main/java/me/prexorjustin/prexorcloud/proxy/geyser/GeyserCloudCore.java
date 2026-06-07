package me.prexorjustin.prexorcloud.proxy.geyser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.api.domain.PlayerEdition;
import me.prexorjustin.prexorcloud.plugin.common.dto.PendingTransferDto;
import me.prexorjustin.prexorcloud.proxy.shared.AbstractProxyCloudPlugin;
import me.prexorjustin.prexorcloud.proxy.shared.PlayerPingSample;

import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.extension.Extension;

/**
 * Geyser-side concrete {@link AbstractProxyCloudPlugin}. Bedrock specifics:
 *
 * <ul>
 *   <li>{@code registerBackend}/{@code unregisterBackend} are intentional no-ops — Geyser has no
 *       server registry to mutate; it forwards to its single configured remote.
 *   <li>{@code transferPlayer} uses Geyser's {@link GeyserConnection#transfer(String, int)}, which
 *       only does anything in the standalone topology (Geyser wired straight to a managed backend);
 *       behind a Java proxy the proxy owns transfers.
 *   <li>session joins are reported with an explicit {@code bedrock} edition.
 * </ul>
 */
final class GeyserCloudCore extends AbstractProxyCloudPlugin {

    private final Extension extension;
    private ScheduledExecutorService scheduler;

    GeyserCloudCore(Extension extension) {
        this.extension = extension;
    }

    boolean start() {
        return initialize();
    }

    void stop() {
        shutdown();
    }

    /** Report a newly connected Bedrock session as edition=bedrock. */
    void onBedrockJoin(GeyserConnection connection) {
        if (controllerClient == null) return;
        controllerClient.reportPlayerJoin(
                connection.javaUuid(),
                displayName(connection),
                PluginEnv.instanceId(),
                PluginEnv.group(),
                PlayerEdition.BEDROCK);
    }

    /** Report a disconnected Bedrock session. */
    void onBedrockLeave(GeyserConnection connection) {
        if (controllerClient == null) return;
        controllerClient.reportPlayerLeave(connection.javaUuid());
    }

    private static String displayName(GeyserConnection connection) {
        String bedrockName = connection.bedrockUsername();
        return bedrockName != null && !bedrockName.isBlank() ? bedrockName : connection.javaUsername();
    }

    @Override
    protected void registerBackend(String instanceId, String address, int port) {
        // No-op: Geyser forwards to its single configured remote and keeps no backend server map.
    }

    @Override
    protected void unregisterBackend(String instanceId) {
        // No-op: see registerBackend.
    }

    @Override
    protected boolean transferPlayer(UUID playerUuid, PendingTransferDto transfer) {
        GeyserConnection connection = extension.geyserApi().connectionByUuid(playerUuid);
        if (connection == null) return false;
        return connection.transfer(transfer.routableAddress(), transfer.port());
    }

    @Override
    protected int currentPlayerCount() {
        return extension.geyserApi().onlineConnections().size();
    }

    @Override
    protected List<PlayerPingSample> collectPings() {
        var samples = new ArrayList<PlayerPingSample>();
        for (GeyserConnection connection : extension.geyserApi().onlineConnections()) {
            samples.add(new PlayerPingSample(connection.javaUuid(), displayName(connection), connection.ping()));
        }
        return samples;
    }

    @Override
    protected void scheduleRepeating(String name, long initialDelay, long period, TimeUnit unit, Runnable task) {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "prexorcloud-geyser-scheduler");
                t.setDaemon(true);
                return t;
            });
        }
        scheduler.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    @Override
    protected void cancelTasks() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    protected void info(String message) {
        extension.logger().info(message);
    }

    @Override
    protected void warn(String message) {
        extension.logger().warning(message);
    }
}
