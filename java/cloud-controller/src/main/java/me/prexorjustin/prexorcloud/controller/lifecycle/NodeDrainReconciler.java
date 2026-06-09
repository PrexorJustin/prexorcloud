package me.prexorjustin.prexorcloud.controller.lifecycle;

import java.time.Instant;
import java.util.Set;

import me.prexorjustin.prexorcloud.api.event.events.NodeDrainCompletedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.NodeDrainIntent;
import me.prexorjustin.prexorcloud.controller.state.NodeState;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.InstanceState;
import me.prexorjustin.prexorcloud.protocol.ShutdownNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns durable node-drain reconciliation, timeout progression, and completion.
 */
public final class NodeDrainReconciler {
    private static final String NODE_DRAIN_LEASE_PREFIX = "node-drain:";

    public interface TimeoutController {
        void schedule(String nodeId, Instant timeoutAt);

        void cancel(String nodeId);
    }

    private static final Logger logger = LoggerFactory.getLogger(NodeDrainReconciler.class);
    private static final Set<InstanceState> TERMINAL_STATES = Set.of(InstanceState.STOPPED, InstanceState.CRASHED);

    private final ClusterState clusterState;
    private final WorkflowStateStore workflowStateStore;
    private final Scheduler scheduler;
    private final NodeSessionManager sessionManager;
    private final EventBus eventBus;
    private final DistributedLeaseManager leaseManager;
    private final TimeoutController timeoutController;

    public NodeDrainReconciler(
            ClusterState clusterState,
            WorkflowStateStore workflowStateStore,
            Scheduler scheduler,
            NodeSessionManager sessionManager,
            EventBus eventBus,
            DistributedLeaseManager leaseManager,
            TimeoutController timeoutController) {
        this.clusterState = clusterState;
        this.workflowStateStore = workflowStateStore;
        this.scheduler = scheduler;
        this.sessionManager = sessionManager;
        this.eventBus = eventBus;
        this.leaseManager = leaseManager;
        this.timeoutController = timeoutController;
    }

    public void scheduleDrainTimeout(String nodeId, Instant timeoutAt) {
        timeoutController.schedule(nodeId, timeoutAt);
    }

    public void handleDrainingInstanceWithoutPlayers(String nodeId, String instanceId) {
        var intent = workflowStateStore.getNodeDrain(nodeId).orElse(null);
        if (intent == null || !intent.drainingInstanceIds().contains(instanceId)) {
            return;
        }

        DistributedLeaseManager.Lease lease = requireDrainLease(nodeId, "playerless draining instance");
        if (leaseManager != null && lease == null) {
            return;
        }
        if (!ensureDrainLeaseCurrent(nodeId, lease, "playerless draining instance")) {
            return;
        }

        updateDrainInstances(nodeId, instanceIdsWithout(intent, instanceId), lease);
        scheduler.stopInstance(instanceId, false);
        logger.debug("All players left draining instance {} on node {} -- stopping", instanceId, nodeId);
    }

    public void onDrainTimeout(String nodeId) {
        DistributedLeaseManager.Lease lease = requireDrainLease(nodeId, "drain timeout");
        if (leaseManager != null && lease == null) {
            return;
        }

        var intent = workflowStateStore.getNodeDrain(nodeId).orElse(null);
        if (intent == null) {
            return;
        }
        if (!ensureDrainLeaseCurrent(nodeId, lease, "drain timeout")) {
            return;
        }

        logger.info("Drain timeout reached for node {} -- stopping remaining instances", nodeId);

        for (String instanceId : Set.copyOf(intent.drainingInstanceIds())) {
            long remaining = clusterState.getAllPlayers().stream()
                    .filter(player -> player.instanceId().equals(instanceId))
                    .count();
            if (remaining == 0) {
                updateDrainInstances(nodeId, instanceIdsWithout(intent, instanceId), lease);
                scheduler.stopInstance(instanceId, false);
            }
        }
    }

    public void checkDrainCompletion(String nodeId) {
        checkDrainCompletion(nodeId, requireDrainLease(nodeId, "drain completion check"));
    }

    public void reconcilePersistedDrains() {
        for (var intent : workflowStateStore.nodeDrains().values()) {
            reconcilePersistedDrain(intent.nodeId());
        }
    }

    public void reconcilePersistedDrain(String nodeId) {
        var intent = workflowStateStore.getNodeDrain(nodeId).orElse(null);
        if (intent == null) {
            return;
        }
        if (clusterState.getNode(intent.nodeId()).isEmpty()) {
            timeoutController.cancel(intent.nodeId());
            workflowStateStore.deleteNodeDrain(intent.nodeId());
            return;
        }

        DistributedLeaseManager.Lease lease = requireDrainLease(intent.nodeId(), "drain reconciliation");
        if (leaseManager != null && lease == null) {
            return;
        }

        for (String instanceId : Set.copyOf(intent.drainingInstanceIds())) {
            long remaining = clusterState.getAllPlayers().stream()
                    .filter(player -> player.instanceId().equals(instanceId))
                    .count();
            if (remaining == 0) {
                if (!ensureDrainLeaseCurrent(intent.nodeId(), lease, "drain reconciliation")) {
                    return;
                }
                updateDrainInstances(intent.nodeId(), instanceIdsWithout(intent, instanceId), lease);
                scheduler.stopInstance(instanceId, false);
            }
        }

        if (!ensureDrainLeaseCurrent(intent.nodeId(), lease, "drain reconciliation")) {
            return;
        }

        if (intent.timeoutAt().isAfter(Instant.now())) {
            timeoutController.schedule(intent.nodeId(), intent.timeoutAt());
        } else {
            onDrainTimeout(intent.nodeId());
        }
        checkDrainCompletion(intent.nodeId(), lease);
    }

    private void checkDrainCompletion(String nodeId, DistributedLeaseManager.Lease lease) {
        if (workflowStateStore.getNodeDrain(nodeId).isEmpty()) {
            return;
        }
        if (leaseManager != null && lease == null) {
            return;
        }
        if (!ensureDrainLeaseCurrent(nodeId, lease, "drain completion check")) {
            return;
        }

        boolean allDone = clusterState.getInstancesByNode(nodeId).stream()
                .allMatch(instance -> TERMINAL_STATES.contains(instance.state()));
        if (allDone) {
            completeDrain(nodeId, lease);
        }
    }

    private void completeDrain(String nodeId, DistributedLeaseManager.Lease lease) {
        var intent = workflowStateStore.getNodeDrain(nodeId).orElse(null);
        if (intent == null) {
            return;
        }
        if (leaseManager != null && lease == null) {
            return;
        }
        if (!ensureDrainLeaseCurrent(nodeId, lease, "drain completion")) {
            return;
        }

        timeoutController.cancel(nodeId);
        workflowStateStore.deleteNodeDrain(nodeId);

        logger.info("Node {} fully drained (shutdown={})", nodeId, intent.shutdownAfterDrain());

        if (intent.shutdownAfterDrain()) {
            sessionManager.getByNodeId(nodeId).ifPresent(session -> {
                var shutdownMessage = ControllerMessage.newBuilder()
                        .setShutdownNode(ShutdownNode.newBuilder().setReason("Node drain completed"))
                        .build();
                session.send(shutdownMessage);
            });
        } else {
            clusterState.setNodeStatus(nodeId, NodeState.NodeStatus.CORDONED);
        }

        eventBus.publish(new NodeDrainCompletedEvent(nodeId, Instant.now()));
    }

    private void updateDrainInstances(
            String nodeId, Set<String> drainingInstanceIds, DistributedLeaseManager.Lease lease) {
        if (leaseManager != null && !ensureDrainLeaseCurrent(nodeId, lease, "drain state update")) {
            return;
        }
        workflowStateStore
                .getNodeDrain(nodeId)
                .ifPresent(intent -> workflowStateStore.saveNodeDrain(
                        intent.withDrainingInstanceIds(Set.copyOf(drainingInstanceIds))));
    }

    private DistributedLeaseManager.Lease requireDrainLease(String nodeId, String action) {
        if (leaseManager == null) {
            return null;
        }
        DistributedLeaseManager.Lease lease =
                leaseManager.tryAcquireLease("node-drain:" + nodeId).orElse(null);
        if (lease == null) {
            timeoutController.cancel(nodeId);
            logger.debug("Skipping {} for node {} because another controller holds the drain lease", action, nodeId);
        }
        return lease;
    }

    private boolean ensureDrainLeaseCurrent(String nodeId, DistributedLeaseManager.Lease lease, String action) {
        if (leaseManager == null || (lease != null && leaseManager.isCurrent(lease))) {
            return true;
        }
        timeoutController.cancel(nodeId);
        logger.info("Aborting {} for node {} because this controller no longer holds the drain lease", action, nodeId);
        return false;
    }

    private Set<String> instanceIdsWithout(NodeDrainIntent intent, String instanceId) {
        return intent.drainingInstanceIds().stream()
                .filter(current -> !current.equals(instanceId))
                .collect(java.util.stream.Collectors.toSet());
    }

    static String nodeIdFromLeaseResource(String resource) {
        if (resource == null
                || !resource.startsWith(NODE_DRAIN_LEASE_PREFIX)
                || resource.length() == NODE_DRAIN_LEASE_PREFIX.length()) {
            return null;
        }
        return resource.substring(NODE_DRAIN_LEASE_PREFIX.length());
    }
}
