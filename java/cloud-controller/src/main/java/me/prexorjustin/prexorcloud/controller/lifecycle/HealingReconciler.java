package me.prexorjustin.prexorcloud.controller.lifecycle;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.HealingActionIntent;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;

/**
 * Owns durable healing intent and replacement reconciliation for crashed instances.
 *
 * <p>Single-writer model: the only controller that drives healing is the leader.
 * The per-instance Redis lease that used to elect the healing owner is gone — the
 * caller ({@link InstanceLifecycleManager}) gates every entry point on
 * {@code leadership.isLeader()}, so this class just does the work.
 */
public final class HealingReconciler {

    @FunctionalInterface
    public interface ReplacementAction {
        void scheduleReplacement(String groupName, String instanceId);
    }

    private final ClusterState clusterState;
    private volatile WorkflowStateStore workflowStateStore;
    private volatile ReplacementAction replacementAction;

    public HealingReconciler(ClusterState clusterState) {
        this.clusterState = clusterState;
    }

    public void attachWorkflow(WorkflowStateStore workflowStateStore, ReplacementAction replacementAction) {
        this.workflowStateStore = workflowStateStore;
        this.replacementAction = replacementAction;
    }

    public void reconcilePersistedHealingActions() {
        var store = workflowStateStore;
        var action = replacementAction;
        if (store == null || action == null) {
            return;
        }

        for (var intent : store.healingActions().values()) {
            reconcilePersistedHealingAction(intent.instanceId());
        }
    }

    public void reconcilePersistedHealingAction(String instanceId) {
        var store = workflowStateStore;
        var action = replacementAction;
        if (store == null || action == null) {
            return;
        }

        var intent = store.getHealingAction(instanceId).orElse(null);
        if (intent == null) {
            return;
        }

        var instance = clusterState.getInstance(intent.instanceId()).orElse(null);
        if (instance != null
                && instance.state() != me.prexorjustin.prexorcloud.protocol.InstanceState.STOPPED
                && instance.state() != me.prexorjustin.prexorcloud.protocol.InstanceState.CRASHED) {
            // The instance recovered on its own — drop the durable intent.
            store.deleteHealingAction(intent.instanceId());
            return;
        }
        action.scheduleReplacement(intent.groupName(), intent.instanceId());
    }

    public void queueHealing(String instanceId, String groupName, String reason) {
        var store = workflowStateStore;
        var action = replacementAction;
        if (store == null || action == null) {
            return;
        }
        if (store.getHealingAction(instanceId).isEmpty()) {
            store.saveHealingAction(new HealingActionIntent(instanceId, groupName, reason, Instant.now()));
        }
        action.scheduleReplacement(groupName, instanceId);
    }

    public void clearHealingIntent(String instanceId) {
        var store = workflowStateStore;
        if (store == null) {
            return;
        }
        store.deleteHealingAction(instanceId);
    }
}
