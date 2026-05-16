package me.prexorjustin.prexorcloud.proxy.velocity;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.client.CloudClient;
import me.prexorjustin.prexorcloud.api.client.TransferResult;
import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.api.domain.InstanceView;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;
import me.prexorjustin.prexorcloud.proxy.shared.ProxyControllerClient;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

/**
 * Proxy-flavoured {@link CloudClient}. Transfers happen locally through the
 * Velocity {@code ProxyServer} — the proxy is the authoritative router, so we
 * do not round-trip through the controller for player movement.
 */
final class VelocityCloudClient implements CloudClient {

    private final ProxyServer proxyServer;
    private final CloudStateCache stateCache;
    private final ProxyControllerClient controllerClient;

    VelocityCloudClient(ProxyServer proxyServer, CloudStateCache stateCache, ProxyControllerClient controllerClient) {
        this.proxyServer = proxyServer;
        this.stateCache = stateCache;
        this.controllerClient = controllerClient;
    }

    @Override
    public String instanceId() {
        return PluginEnv.instanceId();
    }

    @Override
    public CompletableFuture<Void> markReady() {
        return CompletableFuture.runAsync(controllerClient::reportReady);
    }

    @Override
    public CompletableFuture<Void> markStopping() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<TransferResult> transferPlayer(UUID playerId, String targetGroup) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<InstanceView> target = stateCache.getInstancesByGroup(targetGroup).stream()
                    .filter(i -> i.state() == InstanceState.RUNNING)
                    .min((a, b) -> Integer.compare(a.playerCount(), b.playerCount()));
            if (target.isEmpty()) {
                return TransferResult.failure("No running instance in group: " + targetGroup);
            }
            return moveTo(playerId, target.get());
        });
    }

    @Override
    public CompletableFuture<TransferResult> transferPlayerTo(UUID playerId, String targetInstanceId) {
        return CompletableFuture.supplyAsync(() -> stateCache
                .getInstance(targetInstanceId)
                .map(view -> moveTo(playerId, view))
                .orElse(TransferResult.failure("Instance not found: " + targetInstanceId)));
    }

    private TransferResult moveTo(UUID playerId, InstanceView target) {
        return proxyServer
                .getPlayer(playerId)
                .map(player -> {
                    RegisteredServer server = proxyServer
                            .getServer(target.instanceId())
                            .orElseGet(() -> proxyServer.registerServer(new ServerInfo(
                                    target.instanceId(), new InetSocketAddress(target.nodeAddress(), target.port()))));
                    player.createConnectionRequest(server).fireAndForget();
                    return TransferResult.success(target.instanceId());
                })
                .orElse(TransferResult.failure("Player not on this proxy: " + playerId));
    }

    @Override
    public CompletableFuture<InstanceView> fetchInstance(String instanceId) {
        return CompletableFuture.supplyAsync(() -> stateCache
                .getInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId)));
    }

    @Override
    public CompletableFuture<Void> reportCrash(String exitCode, String logTail) {
        return CompletableFuture.completedFuture(null);
    }
}
