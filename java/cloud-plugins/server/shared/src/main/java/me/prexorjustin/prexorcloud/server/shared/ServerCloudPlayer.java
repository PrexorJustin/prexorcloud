package me.prexorjustin.prexorcloud.server.shared;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.domain.PlayerView;
import me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer;

/**
 * Server-side CloudPlayer implementation. Transfers are delegated to the
 * controller via REST; the proxy then executes them.
 */
final class ServerCloudPlayer implements CloudPlayer {

    private final UUID uniqueId;
    private final String name;
    private final String instanceId;
    private final String group;
    private final ServerControllerClient client;

    ServerCloudPlayer(UUID uniqueId, String name, String instanceId, String group, ServerControllerClient client) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.instanceId = instanceId;
        this.group = group;
        this.client = client;
    }

    @Override
    public UUID uniqueId() {
        return uniqueId;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String currentInstanceId() {
        return instanceId;
    }

    @Override
    public String currentGroup() {
        return group;
    }

    @Override
    public PlayerView toView() {
        return new PlayerView(uniqueId, name, instanceId, group, null, Instant.now());
    }

    @Override
    public void sendMessage(String message) {
        // Implemented at the platform level — not available without platform API
    }

    @Override
    public CompletableFuture<Boolean> transfer(String targetGroup) {
        return CompletableFuture.supplyAsync(() -> {
            client.requestTransferToGroup(uniqueId, targetGroup);
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> transferTo(String targetInstanceId) {
        return CompletableFuture.supplyAsync(() -> {
            client.requestTransfer(uniqueId, targetInstanceId);
            return true;
        });
    }

    @Override
    public void kick(String reason) {
        // Implemented at the platform level
    }
}
