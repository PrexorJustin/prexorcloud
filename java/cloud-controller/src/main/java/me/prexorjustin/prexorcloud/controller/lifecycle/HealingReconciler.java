package me.prexorjustin.prexorcloud.controller.lifecycle;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.HealingActionIntent;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns durable healing intent and replacement reconciliation for crashed instances.
 */
public final class HealingReconciler {
    private static final String HEALING_LEASE_PREFIX = "healing:";

    @FunctionalInterface
    public interface ReplacementAction {
        void scheduleReplacement(String groupName, String instanceId);
    }

    private static final Logger logger = LoggerFactory.getLogger(HealingReconciler.class);

    private final ClusterState clusterState;
    private volatile WorkflowStateStore workflowStateStore;
    private volatile ReplacementAction replacementAction;
    private volatile DistributedLeaseManager leaseManager;
    private volatile boolean leaseListenerRegistered;

    public HealingReconciler(ClusterState clusterState) {
        this.clusterState = clusterState;
    }

    public void attachWorkflow(WorkflowStateStore workflowStateStore, ReplacementAction replacementAction) {
        attachWorkflow(workflowStateStore, replacementAction, null);
    }

    public void attachWorkflow(
            WorkflowStateStore workflowStateStore,
            ReplacementAction replacementAction,
            DistributedLeaseManager leaseManager) {
        this.workflowStateStore = workflowStateStore;
        this.replacementAction = replacementAction;
        this.leaseManager = leaseManager;
        if (leaseManager != null && !leaseListenerRegistered) {
            leaseManager.addLeaseChangeListener(this::onLeaseAcquired);
            leaseListenerRegistered = true;
        }
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

        DistributedLeaseManager.Lease lease = requireHealingLease(intent.instanceId(), "healing reconciliation");
        if (leaseManager != null && lease == null) {
            return;
        }
        var instance = clusterState.getInstance(intent.instanceId()).orElse(null);
        if (instance != null
                && instance.state() != me.prexorjustin.prexorcloud.protocol.InstanceState.STOPPED
                && instance.state() != me.prexorjustin.prexorcloud.protocol.InstanceState.CRASHED) {
            if (ensureHealingLeaseCurrent(intent.instanceId(), lease, "healing reconciliation")) {
                store.deleteHealingAction(intent.instanceId());
            }
            return;
        }
        if (!ensureHealingLeaseCurrent(intent.instanceId(), lease, "healing reconciliation")) {
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
        DistributedLeaseManager.Lease lease = requireHealingLease(instanceId, "queue healing");
        if (leaseManager != null && lease == null) {
            return;
        }
        if (store.getHealingAction(instanceId).isEmpty()) {
            store.saveHealingAction(new HealingActionIntent(instanceId, groupName, reason, Instant.now()));
        }
        if (!ensureHealingLeaseCurrent(instanceId, lease, "queue healing")) {
            return;
        }
        action.scheduleReplacement(groupName, instanceId);
    }

    public void clearHealingIntent(String instanceId) {
        var store = workflowStateStore;
        if (store == null) {
            return;
        }
        DistributedLeaseManager.Lease lease = requireHealingLease(instanceId, "clear healing");
        if (leaseManager != null && lease == null) {
            return;
        }
        if (ensureHealingLeaseCurrent(instanceId, lease, "clear healing")) {
            store.deleteHealingAction(instanceId);
        }
    }

    private DistributedLeaseManager.Lease requireHealingLease(String instanceId, String action) {
        var currentLeaseManager = leaseManager;
        if (currentLeaseManager == null) {
            return null;
        }
        DistributedLeaseManager.Lease lease =
                currentLeaseManager.tryAcquireLease("healing:" + instanceId).orElse(null);
        if (lease == null) {
            logger.debug(
                    "Skipping {} for instance {} because another controller holds the healing lease",
                    action,
                    instanceId);
        }
        return lease;
    }

    private boolean ensureHealingLeaseCurrent(String instanceId, DistributedLeaseManager.Lease lease, String action) {
        var currentLeaseManager = leaseManager;
        if (currentLeaseManager == null || (lease != null && currentLeaseManager.isCurrent(lease))) {
            return true;
        }
        logger.info(
                "Aborting {} for instance {} because this controller no longer holds the healing lease",
                action,
                instanceId);
        return false;
    }

    private void onLeaseAcquired(DistributedLeaseManager.Lease lease) {
        String instanceId = instanceIdFromLease(lease.resource());
        if (instanceId == null) {
            return;
        }
        reconcilePersistedHealingAction(instanceId);
    }

    private static String instanceIdFromLease(String resource) {
        if (resource == null
                || !resource.startsWith(HEALING_LEASE_PREFIX)
                || resource.length() == HEALING_LEASE_PREFIX.length()) {
            return null;
        }
        return resource.substring(HEALING_LEASE_PREFIX.length());
    }
}
