package me.prexorjustin.prexorcloud.proxy.bungeecord;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.plugin.common.dto.PendingTransferDto;
import me.prexorjustin.prexorcloud.proxy.shared.AbstractProxyCloudPlugin;
import me.prexorjustin.prexorcloud.proxy.shared.PlayerPingSample;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.scheduler.ScheduledTask;

/**
 * BungeeCord-side concrete implementation of {@link AbstractProxyCloudPlugin}.
 */
final class BungeeCloudCore extends AbstractProxyCloudPlugin {

    private final PrexorCloudBungeeCord owner;
    private final List<ScheduledTask> tasks = new java.util.ArrayList<>();
    private BungeeCloudApi cloudApi;

    BungeeCloudCore(PrexorCloudBungeeCord owner) {
        this.owner = owner;
    }

    boolean start() {
        return initialize();
    }

    void stop() {
        shutdown();
    }

    @Override
    protected void registerBackend(String instanceId, String address, int port) {
        ServerInfo info =
                owner.getProxy().constructServerInfo(instanceId, new InetSocketAddress(address, port), "", false);
        owner.getProxy().getServers().put(instanceId, info);
        owner.getLogger().info("Registered backend server: " + instanceId + " -> " + address + ":" + port);
    }

    @Override
    protected void unregisterBackend(String instanceId) {
        if (owner.getProxy().getServers().containsKey(instanceId)) {
            owner.getProxy().getServers().remove(instanceId);
            owner.getLogger().info("Unregistered backend server: " + instanceId);
        }
    }

    @Override
    protected boolean transferPlayer(UUID playerUuid, PendingTransferDto transfer) {
        if (transfer.targetInstanceId().equals(me.prexorjustin.prexorcloud.api.client.env.PluginEnv.instanceId())) {
            return false;
        }
        var player = owner.getProxy().getPlayer(playerUuid);
        if (player == null) return false;
        ServerInfo serverInfo = owner.getProxy().getServerInfo(transfer.targetInstanceId());
        if (serverInfo == null) {
            serverInfo = owner.getProxy()
                    .constructServerInfo(
                            transfer.targetInstanceId(),
                            new InetSocketAddress(transfer.routableAddress(), transfer.port()),
                            "",
                            false);
            owner.getProxy().getServers().put(transfer.targetInstanceId(), serverInfo);
        }
        player.connect(serverInfo);
        return true;
    }

    @Override
    protected int currentPlayerCount() {
        return owner.getProxy().getPlayers().size();
    }

    @Override
    protected List<PlayerPingSample> collectPings() {
        var samples = new ArrayList<PlayerPingSample>();
        for (var p : owner.getProxy().getPlayers()) {
            samples.add(new PlayerPingSample(p.getUniqueId(), p.getName(), p.getPing()));
        }
        return samples;
    }

    @Override
    protected void scheduleRepeating(String name, long initialDelay, long period, TimeUnit unit, Runnable task) {
        tasks.add(owner.getProxy()
                .getScheduler()
                .schedule(owner, task, unit.toSeconds(initialDelay), unit.toSeconds(period), TimeUnit.SECONDS));
    }

    @Override
    protected void cancelTasks() {
        for (var t : tasks) {
            t.cancel();
        }
        tasks.clear();
    }

    @Override
    protected void onAfterCacheStarted() {
        cloudApi = new BungeeCloudApi(stateCache, owner);
        cloudApi.start();

        owner.getProxy()
                .getPluginManager()
                .registerListener(
                        owner,
                        new BungeePlayerListener(controllerClient, cloudApi.events(), stateCache, owner.getProxy()));
    }

    @Override
    protected void info(String message) {
        owner.getLogger().info(message);
    }

    @Override
    protected void warn(String message) {
        owner.getLogger().warning(message);
    }
}
