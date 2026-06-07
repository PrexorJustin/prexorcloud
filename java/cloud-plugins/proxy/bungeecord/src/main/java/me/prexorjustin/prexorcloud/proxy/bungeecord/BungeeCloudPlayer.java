package me.prexorjustin.prexorcloud.proxy.bungeecord;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.api.domain.PlayerView;
import me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BungeeCloudPlayer implements CloudPlayer {

    private static final Logger logger = LoggerFactory.getLogger(BungeeCloudPlayer.class);

    private final ProxiedPlayer player;
    private final CloudStateCache stateCache;

    public BungeeCloudPlayer(ProxiedPlayer player, CloudStateCache stateCache) {
        this.player = player;
        this.stateCache = stateCache;
    }

    @Override
    public UUID uniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public String currentInstanceId() {
        return player.getServer() != null ? player.getServer().getInfo().getName() : "";
    }

    @Override
    public String currentGroup() {
        String currentServer = currentInstanceId();
        if (currentServer.isEmpty()) return "";
        return stateCache.getInstance(currentServer).map(i -> i.group()).orElse("");
    }

    @Override
    public PlayerView toView() {
        return new PlayerView(uniqueId(), name(), currentInstanceId(), currentGroup(), null, Instant.now());
    }

    @Override
    public void sendMessage(String message) {
        player.sendMessage(new TextComponent(message));
    }

    @Override
    public CompletableFuture<Boolean> transfer(String targetGroup) {
        return CompletableFuture.supplyAsync(() -> {
            stateCache.getInstancesByGroup(targetGroup).stream()
                    .filter(i -> i.state() == InstanceState.RUNNING)
                    .min(Comparator.comparingInt(i -> i.playerCount()))
                    .ifPresent(i -> connectTo(i.instanceId(), i.nodeAddress(), i.port()));
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> transferTo(String targetInstanceId) {
        return CompletableFuture.supplyAsync(() -> {
            var instanceOpt = stateCache.getInstance(targetInstanceId);
            if (instanceOpt.isEmpty()) {
                logger.warn("Cannot transfer {} to {}: instance not in cache", player.getName(), targetInstanceId);
                return false;
            }
            var instance = instanceOpt.get();
            connectTo(targetInstanceId, instance.nodeAddress(), instance.port());
            return true;
        });
    }

    @Override
    public void kick(String reason) {
        player.disconnect(reason);
    }

    private void connectTo(String instanceId, String nodeAddress, int port) {
        var proxy = ProxyServer.getInstance();
        net.md_5.bungee.api.config.ServerInfo serverInfo = proxy.getServerInfo(instanceId);
        if (serverInfo == null) {
            serverInfo = proxy.constructServerInfo(instanceId, new InetSocketAddress(nodeAddress, port), "", false);
            proxy.getServers().put(instanceId, serverInfo);
        }
        player.connect(serverInfo);
    }
}
