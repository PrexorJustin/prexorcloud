package me.prexorjustin.prexorcloud.proxy.velocity;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.plugin.common.dto.PendingTransferDto;
import me.prexorjustin.prexorcloud.proxy.shared.AbstractProxyCloudPlugin;
import me.prexorjustin.prexorcloud.proxy.shared.PlayerPingSample;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

/**
 * Velocity-side concrete implementation of {@link AbstractProxyCloudPlugin}.
 * The {@code @Plugin} entry class delegates onProxyInit / onProxyShutdown into
 * this core, and registers Velocity event listeners after init.
 */
final class VelocityCloudCore extends AbstractProxyCloudPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final PrexorCloudVelocity owner;

    private VelocityCloudApi cloudApi;
    private ScheduledExecutorService scheduler;

    VelocityCloudCore(ProxyServer proxyServer, Logger logger, PrexorCloudVelocity owner) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.owner = owner;
    }

    boolean start() {
        return initialize();
    }

    void stop() {
        shutdown();
    }

    VelocityCloudApi cloudApi() {
        return cloudApi;
    }

    @Override
    protected void registerBackend(String instanceId, String address, int port) {
        ServerInfo info = new ServerInfo(instanceId, new InetSocketAddress(address, port));
        proxyServer.registerServer(info);
        logger.info("Registered backend server: {} -> {}:{}", instanceId, address, port);
    }

    @Override
    protected void unregisterBackend(String instanceId) {
        proxyServer.getServer(instanceId).ifPresent(s -> {
            proxyServer.unregisterServer(s.getServerInfo());
            logger.info("Unregistered backend server: {}", instanceId);
        });
    }

    @Override
    protected boolean transferPlayer(UUID playerUuid, PendingTransferDto transfer) {
        var player = proxyServer.getPlayer(playerUuid).orElse(null);
        if (player == null) return false;
        RegisteredServer registeredServer = proxyServer
                .getServer(transfer.targetInstanceId())
                .orElseGet(() -> proxyServer.registerServer(new ServerInfo(
                        transfer.targetInstanceId(),
                        new InetSocketAddress(transfer.routableAddress(), transfer.port()))));
        player.createConnectionRequest(registeredServer).fireAndForget();
        return true;
    }

    @Override
    protected boolean supportsCrossProxyMessages() {
        return true;
    }

    @Override
    protected boolean deliverMessage(UUID toUuid, String fromName, String content) {
        var player = proxyServer.getPlayer(toUuid).orElse(null);
        if (player == null) return false;
        Component formatted = Component.text("[" + fromName + " → you] ", NamedTextColor.GOLD)
                .append(Component.text(content, NamedTextColor.WHITE));
        player.sendMessage(formatted);
        return true;
    }

    @Override
    protected int currentPlayerCount() {
        return proxyServer.getAllPlayers().size();
    }

    @Override
    protected List<PlayerPingSample> collectPings() {
        var samples = new ArrayList<PlayerPingSample>();
        for (var p : proxyServer.getAllPlayers()) {
            samples.add(new PlayerPingSample(p.getUniqueId(), p.getUsername(), (int) p.getPing()));
        }
        return samples;
    }

    @Override
    protected void scheduleRepeating(String name, long initialDelay, long period, TimeUnit unit, Runnable task) {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "prexorcloud-proxy-scheduler");
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
    protected void onAfterCacheStarted() {
        cloudApi = new VelocityCloudApi(proxyServer, stateCache, controllerClient);
        cloudApi.start();

        proxyServer
                .getEventManager()
                .register(
                        owner,
                        new VelocityPlayerListener(proxyServer, controllerClient, stateCache, cloudApi.events()));
        proxyServer.getEventManager().register(owner, new VelocityPingListener(stateCache));
    }

    @Override
    protected void info(String message) {
        logger.info(message);
    }

    @Override
    protected void warn(String message) {
        logger.warn(message);
    }
}
