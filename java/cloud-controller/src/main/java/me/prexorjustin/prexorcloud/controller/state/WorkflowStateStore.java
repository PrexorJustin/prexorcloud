package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkflowStateStore {

    private final Map<UUID, TransferIntent> pendingTransfers = new ConcurrentHashMap<>();
    private final Map<String, NodeDrainIntent> nodeDrains = new ConcurrentHashMap<>();
    private final Map<String, HealingActionIntent> healingActions = new ConcurrentHashMap<>();
    private final Map<String, StartRetryIntent> startRetries = new ConcurrentHashMap<>();
    private final StateStore stateStore;

    public WorkflowStateStore() {
        this.stateStore = null;
    }

    public WorkflowStateStore(StateStore stateStore) {
        this.stateStore = stateStore;
        hydrateFrom(stateStore);
    }

    private void hydrateFrom(StateStore source) {
        pendingTransfers.clear();
        source.getTransferIntents().forEach(intent -> pendingTransfers.put(intent.playerUuid(), intent));
        nodeDrains.clear();
        source.getNodeDrainIntents().forEach(intent -> nodeDrains.put(intent.nodeId(), intent));
        healingActions.clear();
        source.getHealingActionIntents().forEach(intent -> healingActions.put(intent.instanceId(), intent));
        startRetries.clear();
        source.getStartRetryIntents().forEach(intent -> startRetries.put(intent.instanceId(), intent));
    }

    public void queueTransfer(UUID playerUuid, String targetInstanceId) {
        var intent = new TransferIntent(playerUuid, targetInstanceId, Instant.now());
        pendingTransfers.put(playerUuid, intent);
        if (stateStore != null) stateStore.saveTransferIntent(intent);
    }

    public void ackTransfer(UUID playerUuid) {
        pendingTransfers.remove(playerUuid);
        if (stateStore != null) stateStore.deleteTransferIntent(playerUuid);
    }

    public Map<UUID, String> pendingTransfers() {
        var result = new ConcurrentHashMap<UUID, String>();
        pendingTransfers.forEach((playerUuid, intent) -> result.put(playerUuid, intent.targetInstanceId()));
        return Collections.unmodifiableMap(result);
    }

    public Map<UUID, TransferIntent> transferIntents() {
        return Collections.unmodifiableMap(pendingTransfers);
    }

    public void saveNodeDrain(NodeDrainIntent intent) {
        nodeDrains.put(intent.nodeId(), intent);
        if (stateStore != null) stateStore.saveNodeDrainIntent(intent);
    }

    public Optional<NodeDrainIntent> getNodeDrain(String nodeId) {
        return Optional.ofNullable(nodeDrains.get(nodeId));
    }

    public Map<String, NodeDrainIntent> nodeDrains() {
        return Collections.unmodifiableMap(nodeDrains);
    }

    public void deleteNodeDrain(String nodeId) {
        nodeDrains.remove(nodeId);
        if (stateStore != null) stateStore.deleteNodeDrainIntent(nodeId);
    }

    public void saveHealingAction(HealingActionIntent intent) {
        healingActions.put(intent.instanceId(), intent);
        if (stateStore != null) stateStore.saveHealingActionIntent(intent);
    }

    public Optional<HealingActionIntent> getHealingAction(String instanceId) {
        return Optional.ofNullable(healingActions.get(instanceId));
    }

    public Map<String, HealingActionIntent> healingActions() {
        return Collections.unmodifiableMap(healingActions);
    }

    public void deleteHealingAction(String instanceId) {
        healingActions.remove(instanceId);
        if (stateStore != null) stateStore.deleteHealingActionIntent(instanceId);
    }

    public void saveStartRetry(StartRetryIntent intent) {
        startRetries.put(intent.instanceId(), intent);
        if (stateStore != null) stateStore.saveStartRetryIntent(intent);
    }

    public Optional<StartRetryIntent> getStartRetry(String instanceId) {
        return Optional.ofNullable(startRetries.get(instanceId));
    }

    public Map<String, StartRetryIntent> startRetries() {
        return Collections.unmodifiableMap(startRetries);
    }

    public void deleteStartRetry(String instanceId) {
        startRetries.remove(instanceId);
        if (stateStore != null) stateStore.deleteStartRetryIntent(instanceId);
    }
}
