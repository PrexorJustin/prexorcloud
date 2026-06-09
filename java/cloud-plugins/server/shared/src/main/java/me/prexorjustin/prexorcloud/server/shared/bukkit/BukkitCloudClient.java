package me.prexorjustin.prexorcloud.server.shared.bukkit;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.client.CloudClient;
import me.prexorjustin.prexorcloud.api.client.TransferResult;
import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.api.domain.InstanceView;
import me.prexorjustin.prexorcloud.server.shared.ServerControllerClient;

/**
 * Adapts {@link ServerControllerClient} to the public {@link CloudClient}
 * interface. Shared by all Bukkit-based platforms (Spigot/Paper/Folia).
 */
public final class BukkitCloudClient implements CloudClient {

    private final ServerControllerClient client;

    public BukkitCloudClient(ServerControllerClient client) {
        this.client = client;
    }

    @Override
    public String instanceId() {
        return PluginEnv.instanceId();
    }

    @Override
    public CompletableFuture<Void> markReady() {
        return CompletableFuture.runAsync(client::reportReady);
    }

    @Override
    public CompletableFuture<Void> markStopping() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<TransferResult> transferPlayer(UUID playerId, String targetGroup) {
        return CompletableFuture.supplyAsync(() -> {
            client.requestTransferToGroup(playerId, targetGroup);
            return TransferResult.success(targetGroup);
        });
    }

    @Override
    public CompletableFuture<TransferResult> transferPlayerTo(UUID playerId, String targetInstanceId) {
        return CompletableFuture.supplyAsync(() -> {
            client.requestTransfer(playerId, targetInstanceId);
            return TransferResult.success(targetInstanceId);
        });
    }

    @Override
    public CompletableFuture<InstanceView> fetchInstance(String instanceId) {
        return CompletableFuture.supplyAsync(() -> {
            return client.fetchInstances().stream()
                    .filter(dto -> instanceId.equals(dto.instanceId()))
                    .map(dto -> {
                        InstanceState state;
                        try {
                            state = InstanceState.valueOf(dto.state());
                        } catch (IllegalArgumentException e) {
                            state = InstanceState.RUNNING;
                        }
                        return new InstanceView(
                                dto.instanceId(),
                                dto.group(),
                                dto.nodeId(),
                                dto.nodeAddress() != null ? dto.nodeAddress() : dto.nodeId(),
                                state,
                                dto.port(),
                                dto.playerCount(),
                                dto.uptimeMs(),
                                dto.startedAt() != null ? dto.startedAt() : Instant.EPOCH);
                    })
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));
        });
    }

    @Override
    public CompletableFuture<Void> reportCrash(String exitCode, String logTail) {
        return CompletableFuture.completedFuture(null);
    }
}
