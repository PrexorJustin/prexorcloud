package me.prexorjustin.prexorcloud.api.module.instance;

import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.domain.InstanceView;

/** Full lifecycle control for server instances — controller-level only. */
public interface InstanceController {

    /** Schedules a new instance in {@code group} and returns its instance ID. */
    CompletableFuture<String> scheduleInstance(String group);

    /** Schedules a new instance in {@code group} on a specific node. */
    CompletableFuture<String> scheduleInstanceOnNode(String group, String nodeId);

    CompletableFuture<Void> stopInstance(String instanceId);

    CompletableFuture<Void> stopInstance(String instanceId, String kickMessage);

    /** Drains the instance (prevents new players) then stops it. */
    CompletableFuture<Void> drainInstance(String instanceId, String kickMessage);

    CompletableFuture<InstanceView> getInstance(String instanceId);
}
