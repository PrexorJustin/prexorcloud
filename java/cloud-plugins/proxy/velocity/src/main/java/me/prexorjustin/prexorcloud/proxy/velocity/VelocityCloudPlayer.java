package me.prexorjustin.prexorcloud.proxy.velocity;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.api.domain.PlayerView;
import me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VelocityCloudPlayer implements CloudPlayer {

    private static final Logger logger = LoggerFactory.getLogger(VelocityCloudPlayer.class);

    private final Player player;
    private final ProxyServer proxyServer;
    private final CloudStateCache stateCache;

    public VelocityCloudPlayer(Player player, ProxyServer proxyServer, CloudStateCache stateCache) {
        this.player = player;
        this.proxyServer = proxyServer;
        this.stateCache = stateCache;
    }

    @Override
    public UUID uniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getUsername();
    }

    @Override
    public String currentInstanceId() {
        return player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
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
        player.sendMessage(Component.text(message));
    }

    @Override
    public CompletableFuture<Boolean> transfer(String targetGroup) {
        return CompletableFuture.supplyAsync(() -> {
            stateCache.getInstancesByGroup(targetGroup).stream()
                    .filter(i -> i.state() == InstanceState.RUNNING && !i.warm())
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
                logger.warn("Cannot transfer {} to {}: instance not in cache", player.getUsername(), targetInstanceId);
                return false;
            }
            var instance = instanceOpt.get();
            connectTo(targetInstanceId, instance.nodeAddress(), instance.port());
            return true;
        });
    }

    @Override
    public void kick(String reason) {
        player.disconnect(Component.text(reason));
    }

    private void connectTo(String instanceId, String nodeAddress, int port) {
        RegisteredServer registeredServer = proxyServer
                .getServer(instanceId)
                .orElseGet(() -> proxyServer.registerServer(
                        new ServerInfo(instanceId, new InetSocketAddress(nodeAddress, port))));
        player.createConnectionRequest(registeredServer).fireAndForget();
    }
}
