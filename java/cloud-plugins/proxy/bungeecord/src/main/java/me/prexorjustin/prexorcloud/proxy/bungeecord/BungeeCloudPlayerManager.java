package me.prexorjustin.prexorcloud.proxy.bungeecord;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer;
import me.prexorjustin.prexorcloud.api.plugin.player.PlayerManager;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;

import net.md_5.bungee.api.ProxyServer;

public final class BungeeCloudPlayerManager implements PlayerManager {

    private final CloudStateCache stateCache;

    public BungeeCloudPlayerManager(CloudStateCache stateCache) {
        this.stateCache = stateCache;
    }

    @Override
    public Optional<CloudPlayer> getPlayer(UUID uuid) {
        return Optional.ofNullable(ProxyServer.getInstance().getPlayer(uuid))
                .map(p -> new BungeeCloudPlayer(p, stateCache));
    }

    @Override
    public Optional<CloudPlayer> getPlayer(String name) {
        return Optional.ofNullable(ProxyServer.getInstance().getPlayer(name))
                .map(p -> new BungeeCloudPlayer(p, stateCache));
    }

    @Override
    public Collection<CloudPlayer> onlinePlayers() {
        return ProxyServer.getInstance().getPlayers().stream()
                .map(p -> (CloudPlayer) new BungeeCloudPlayer(p, stateCache))
                .toList();
    }

    @Override
    public int onlineCount() {
        return ProxyServer.getInstance().getPlayers().size();
    }
}
