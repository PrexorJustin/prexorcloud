package me.prexorjustin.prexorcloud.api.client;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.domain.InstanceView;

/**
 * Low-level client for communicating with the PrexorCloud controller from a
 * plugin instance.
 */
public interface CloudClient {

    String instanceId();

    /** Notifies the controller that this instance is ready to accept players. */
    CompletableFuture<Void> markReady();

    /** Notifies the controller that this instance is stopping. */
    CompletableFuture<Void> markStopping();

    CompletableFuture<TransferResult> transferPlayer(UUID playerId, String targetGroup);

    CompletableFuture<TransferResult> transferPlayerTo(UUID playerId, String targetInstanceId);

    CompletableFuture<InstanceView> fetchInstance(String instanceId);

    CompletableFuture<Void> reportCrash(String exitCode, String logTail);
}
