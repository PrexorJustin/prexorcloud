package me.prexorjustin.prexorcloud.controller.lifecycle;

import java.time.Instant;
import java.util.Set;

import me.prexorjustin.prexorcloud.api.event.events.NodeDrainCompletedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
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
 *
 * <p>Single-writer model: a plain worker driven only by the leader. The per-node
 * Redis drain lease that used to elect the drain owner is gone — {@link NodeDrainManager}
 * gates every event handler, the reconcile tick, and the timeout firing on
 * {@code leadership.isLeader()}, so this class never runs on a follower.
 */
public final class NodeDrainReconciler {

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
    private final TimeoutController timeoutController;

    public NodeDrainReconciler(
            ClusterState clusterState,
            WorkflowStateStore workflowStateStore,
            Scheduler scheduler,
            NodeSessionManager sessionManager,
            EventBus eventBus,
            TimeoutController timeoutController) {
        this.clusterState = clusterState;
        this.workflowStateStore = workflowStateStore;
        this.scheduler = scheduler;
        this.sessionManager = sessionManager;
        this.eventBus = eventBus;
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
        updateDrainInstances(nodeId, instanceIdsWithout(intent, instanceId));
        scheduler.stopInstance(instanceId, false);
        logger.debug("All players left draining instance {} on node {} -- stopping", instanceId, nodeId);
    }

    public void onDrainTimeout(String nodeId) {
        var intent = workflowStateStore.getNodeDrain(nodeId).orElse(null);
        if (intent == null) {
            return;
        }

        logger.info("Drain timeout reached for node {} -- stopping remaining instances", nodeId);

        for (String instanceId : Set.copyOf(intent.drainingInstanceIds())) {
            long remaining = clusterState.getAllPlayers().stream()
                    .filter(player -> player.instanceId().equals(instanceId))
                    .count();
            if (remaining == 0) {
                updateDrainInstances(nodeId, instanceIdsWithout(intent, instanceId));
                scheduler.stopInstance(instanceId, false);
            }
        }
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

        for (String instanceId : Set.copyOf(intent.drainingInstanceIds())) {
            long remaining = clusterState.getAllPlayers().stream()
                    .filter(player -> player.instanceId().equals(instanceId))
                    .count();
            if (remaining == 0) {
                updateDrainInstances(intent.nodeId(), instanceIdsWithout(intent, instanceId));
                scheduler.stopInstance(instanceId, false);
            }
        }

        if (intent.timeoutAt().isAfter(Instant.now())) {
            timeoutController.schedule(intent.nodeId(), intent.timeoutAt());
        } else {
            onDrainTimeout(intent.nodeId());
        }
        checkDrainCompletion(intent.nodeId());
    }

    public void checkDrainCompletion(String nodeId) {
        if (workflowStateStore.getNodeDrain(nodeId).isEmpty()) {
            return;
        }
        boolean allDone = clusterState.getInstancesByNode(nodeId).stream()
                .allMatch(instance -> TERMINAL_STATES.contains(instance.state()));
        if (allDone) {
            completeDrain(nodeId);
        }
    }

    private void completeDrain(String nodeId) {
        var intent = workflowStateStore.getNodeDrain(nodeId).orElse(null);
        if (intent == null) {
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

    private void updateDrainInstances(String nodeId, Set<String> drainingInstanceIds) {
        workflowStateStore
                .getNodeDrain(nodeId)
                .ifPresent(intent -> workflowStateStore.saveNodeDrain(
                        intent.withDrainingInstanceIds(Set.copyOf(drainingInstanceIds))));
    }

    private Set<String> instanceIdsWithout(NodeDrainIntent intent, String instanceId) {
        return intent.drainingInstanceIds().stream()
                .filter(current -> !current.equals(instanceId))
                .collect(java.util.stream.Collectors.toSet());
    }
}
