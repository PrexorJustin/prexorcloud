package me.prexorjustin.prexorcloud.proxy.velocity;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer;
import me.prexorjustin.prexorcloud.api.plugin.player.PlayerManager;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;

import com.velocitypowered.api.proxy.ProxyServer;

public final class VelocityCloudPlayerManager implements PlayerManager {

    private final ProxyServer proxyServer;
    private final CloudStateCache stateCache;

    public VelocityCloudPlayerManager(ProxyServer proxyServer, CloudStateCache stateCache) {
        this.proxyServer = proxyServer;
        this.stateCache = stateCache;
    }

    @Override
    public Optional<CloudPlayer> getPlayer(UUID uuid) {
        return proxyServer.getPlayer(uuid).map(p -> new VelocityCloudPlayer(p, proxyServer, stateCache));
    }

    @Override
    public Optional<CloudPlayer> getPlayer(String name) {
        return proxyServer.getPlayer(name).map(p -> new VelocityCloudPlayer(p, proxyServer, stateCache));
    }

    @Override
    public Collection<CloudPlayer> onlinePlayers() {
        return proxyServer.getAllPlayers().stream()
                .map(p -> (CloudPlayer) new VelocityCloudPlayer(p, proxyServer, stateCache))
                .toList();
    }

    @Override
    public int onlineCount() {
        return proxyServer.getAllPlayers().size();
    }
}
