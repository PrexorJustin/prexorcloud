package me.prexorjustin.prexorcloud.server.shared;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer;
import me.prexorjustin.prexorcloud.api.plugin.player.PlayerManager;

/**
 * Server-side player manager. Uses a local cache for players on this instance
 * (updated on join/leave) and falls back to HTTP for cross-instance queries.
 */
public final class ServerCloudPlayerManager implements PlayerManager {

    private static final Logger logger = Logger.getLogger(ServerCloudPlayerManager.class.getName());

    private final ServerControllerClient client;
    private final ConcurrentHashMap<UUID, ServerCloudPlayer> localCache = new ConcurrentHashMap<>();

    ServerCloudPlayerManager(ServerControllerClient client) {
        this.client = client;
    }

    /** Called by the platform plugin on player join. */
    public void addLocalPlayer(UUID uuid, String name, String instanceId, String group) {
        localCache.put(uuid, new ServerCloudPlayer(uuid, name, instanceId, group, client));
    }

    /** Called by the platform plugin on player quit. */
    public void removeLocalPlayer(UUID uuid) {
        localCache.remove(uuid);
    }

    int localPlayerCount() {
        return localCache.size();
    }

    @Override
    public Optional<CloudPlayer> getPlayer(UUID uuid) {
        ServerCloudPlayer local = localCache.get(uuid);
        if (local != null) return Optional.of(local);
        try {
            return client.fetchPlayers().stream()
                    .filter(p -> uuid.toString().equals(p.id()))
                    .map(p -> (CloudPlayer)
                            new ServerCloudPlayer(UUID.fromString(p.id()), p.name(), p.instanceId(), p.group(), client))
                    .findFirst();
        } catch (Exception e) {
            logger.fine("Failed to fetch player " + uuid + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<CloudPlayer> getPlayer(String name) {
        Optional<CloudPlayer> local = localCache.values().stream()
                .filter(p -> name.equalsIgnoreCase(p.name()))
                .map(p -> (CloudPlayer) p)
                .findFirst();
        if (local.isPresent()) return local;
        try {
            return client.fetchPlayers().stream()
                    .filter(p -> name.equalsIgnoreCase(p.name()))
                    .map(p -> (CloudPlayer)
                            new ServerCloudPlayer(UUID.fromString(p.id()), p.name(), p.instanceId(), p.group(), client))
                    .findFirst();
        } catch (Exception e) {
            logger.fine("Failed to fetch player by name " + name + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Collection<CloudPlayer> onlinePlayers() {
        try {
            return client.fetchPlayers().stream()
                    .map(p -> (CloudPlayer)
                            new ServerCloudPlayer(UUID.fromString(p.id()), p.name(), p.instanceId(), p.group(), client))
                    .toList();
        } catch (Exception e) {
            logger.fine("Failed to fetch online players: " + e.getMessage());
            return List.copyOf(localCache.values());
        }
    }

    @Override
    public int onlineCount() {
        try {
            return client.fetchPlayers().size();
        } catch (Exception e) {
            logger.fine("Failed to fetch online count: " + e.getMessage());
            return localCache.size();
        }
    }
}
