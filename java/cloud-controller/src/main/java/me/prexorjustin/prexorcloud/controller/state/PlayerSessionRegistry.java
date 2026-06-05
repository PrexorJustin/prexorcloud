package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerSessionRegistry {

    private final Map<UUID, PlayerInfo> players = new ConcurrentHashMap<>();

    public void hydrate(Map<UUID, PlayerInfo> snapshot) {
        players.clear();
        players.putAll(snapshot);
    }

    public PlayerMutationResult addReportedByBackend(UUID uuid, String name, String instanceId, String group) {
        PlayerMutationResult[] result = {null};
        players.compute(uuid, (ignored, existing) -> {
            boolean created = existing == null;
            String proxyId = existing != null ? existing.proxyInstanceId() : "";
            Instant connectedAt = existing != null ? existing.connectedAt() : Instant.now();
            var updated =
                    new PlayerInfo(uuid, name, instanceId, group, proxyId, connectedAt, PlayerEdition.detect(uuid));
            result[0] = new PlayerMutationResult(updated, existing, created);
            return updated;
        });
        return result[0];
    }

    public PlayerMutationResult addReportedByProxy(
            UUID uuid, String name, String instanceId, String group, String proxyInstanceId) {
        var updated = new PlayerInfo(
                uuid, name, instanceId, group, proxyInstanceId, Instant.now(), PlayerEdition.detect(uuid));
        var previous = players.put(uuid, updated);
        return new PlayerMutationResult(updated, previous, previous == null);
    }

    public PlayerInfo remove(UUID uuid) {
        return players.remove(uuid);
    }

    public void removeByInstance(String instanceId) {
        players.values()
                .removeIf(player ->
                        instanceId.equals(player.instanceId()) || instanceId.equals(player.proxyInstanceId()));
    }

    public Optional<PlayerInfo> get(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    public Collection<PlayerInfo> getAll() {
        return Collections.unmodifiableCollection(players.values());
    }

    public int count() {
        return players.size();
    }

    public record PlayerMutationResult(PlayerInfo player, PlayerInfo previous, boolean created) {}
}
