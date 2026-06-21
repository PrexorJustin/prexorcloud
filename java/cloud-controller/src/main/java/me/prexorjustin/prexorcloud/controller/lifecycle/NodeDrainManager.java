package me.prexorjustin.prexorcloud.controller.lifecycle;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.event.events.InstanceStateChangedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeDrainCompletedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeDrainRequestedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerDisconnectedEvent;
import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.NodeDrainIntent;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the node drain lifecycle with graceful player handling:
 * <ol>
 * <li>On {@link NodeDrainRequestedEvent}: transition player-occupied instances
 * to DRAINING, queue player transfers, stop empty instances immediately</li>
 * <li>On {@link PlayerDisconnectedEvent}: stop DRAINING instances that become
 * empty</li>
 * <li>On drain timeout: kick remaining players with a custom message</li>
 * <li>On {@link InstanceStateChangedEvent}: check if a draining node is now
 * fully drained</li>
 * <li>When fully drained: send {@code ShutdownNode} or set CORDONED, emit
 * {@link NodeDrainCompletedEvent}</li>
 * </ol>
 */
public final class NodeDrainManager {

    private static final Logger logger = LoggerFactory.getLogger(NodeDrainManager.class);
    private static final long RECONCILE_INTERVAL_SECONDS = 5;

    /**
     * Terminal states (protocol enum — for comparing with InstanceInfo.state()).
     */
    private static final Set<InstanceState> TERMINAL_STATES = Set.of(InstanceState.STOPPED, InstanceState.CRASHED);
    /**
     * Terminal states (module-api enum — for comparing with event
     * newState/oldState).
     */
    private static final Set<me.prexorjustin.prexorcloud.api.domain.InstanceState> EVENT_TERMINAL_STATES = Set.of(
            me.prexorjustin.prexorcloud.api.domain.InstanceState.STOPPED,
            me.prexorjustin.prexorcloud.api.domain.InstanceState.CRASHED);

    private final ClusterState clusterState;
    private final WorkflowStateStore workflowStateStore;
    private final Scheduler scheduler;
    private final GroupManager groupManager;
    private final NodeDrainReconciler drainReconciler;
    private final ScheduledExecutorService timeoutExecutor;
    private final Map<String, ScheduledFuture<?>> drainTimeouts = new ConcurrentHashMap<>();
    private final ScheduledFuture<?> reconcileTask;
    // Single-writer authority: only the leader drives node drains. Defaults to always-leader so
    // single-controller installs and tests behave unchanged; bootstrap injects the real elector.
    private volatile Leadership leadership = Leadership.alwaysLeader();

    public NodeDrainManager(
            ClusterState clusterState,
            WorkflowStateStore workflowStateStore,
            Scheduler scheduler,
            NodeSessionManager sessionManager,
            EventBus eventBus,
            GroupManager groupManager) {
        this.clusterState = clusterState;
        this.workflowStateStore = workflowStateStore;
        this.scheduler = scheduler;
        this.groupManager = groupManager;
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drain-timeout");
            t.setDaemon(true);
            return t;
        });
        this.drainReconciler = new NodeDrainReconciler(
                clusterState,
                workflowStateStore,
                scheduler,
                sessionManager,
                eventBus,
                new NodeDrainReconciler.TimeoutController() {
                    @Override
                    public void schedule(String nodeId, Instant timeoutAt) {
                        scheduleDrainTimeout(nodeId, timeoutAt);
                    }

                    @Override
                    public void cancel(String nodeId) {
                        cancelDrainTimeout(nodeId);
                    }
                });

        eventBus.subscribe(NodeDrainRequestedEvent.class, this::onDrainRequested);
        eventBus.subscribe(InstanceStateChangedEvent.class, this::onInstanceStateChanged);
        eventBus.subscribe(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
        // Runs on every controller but no-ops unless we're the leader (guarded in
        // reconcilePersistedDrainsSafely) so a fresh leader resumes in-flight drains.
        this.reconcileTask = timeoutExecutor.scheduleWithFixedDelay(
                this::reconcilePersistedDrainsSafely,
                RECONCILE_INTERVAL_SECONDS,
                RECONCILE_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        logger.debug("NodeDrainManager initialized");
    }

    /** Inject single-writer leadership (bootstrap). Tests run as always-leader. */
    public void setLeadership(Leadership leadership) {
        this.leadership = leadership;
    }

    private void onDrainRequested(NodeDrainRequestedEvent event) {
        if (!leadership.isLeader()) {
            return;
        }
        String nodeId = event.nodeId();
        if (workflowStateStore.getNodeDrain(nodeId).isPresent()) {
            logger.debug("Node {} is already draining; ignoring duplicate drain request", nodeId);
            return;
        }
        String kickMessage = event.kickMessage();
        int timeoutSeconds = event.drainTimeoutSeconds();

        var instances = clusterState.getInstancesByNode(nodeId);
        var active = instances.stream()
                .filter(i -> !TERMINAL_STATES.contains(i.state())
                        && i.state() != InstanceState.STOPPING
                        && i.state() != InstanceState.DRAINING)
                .toList();

        if (active.isEmpty()) {
            logger.debug("Node {} has no active instances, completing drain immediately", nodeId);
            workflowStateStore.saveNodeDrain(new NodeDrainIntent(
                    nodeId, event.shutdownAfterDrain(), kickMessage, event.timestamp(), event.timestamp(), Set.of()));
            drainReconciler.checkDrainCompletion(nodeId);
            return;
        }

        var drainingIds = ConcurrentHashMap.<String>newKeySet();

        for (InstanceInfo instance : active) {
            if (instance.playerCount() > 0) {
                // Transition to DRAINING — players will be transferred out
                clusterState.updateInstanceState(instance.id(), InstanceState.DRAINING);
                drainingIds.add(instance.id());
                queueTransfersForInstance(instance, nodeId, kickMessage);
            } else {
                // No players — stop immediately
                scheduler.stopInstance(instance.id(), false);
            }
        }

        var requestedAt = event.timestamp();
        var timeoutAt = requestedAt.plusSeconds(timeoutSeconds);
        workflowStateStore.saveNodeDrain(new NodeDrainIntent(
                nodeId, event.shutdownAfterDrain(), kickMessage, requestedAt, timeoutAt, Set.copyOf(drainingIds)));
        drainReconciler.scheduleDrainTimeout(nodeId, timeoutAt);

        logger.info(
                "Draining node {}: {} instance(s) draining players, {} stopped immediately "
                        + "(shutdown={}, timeout={}s)",
                nodeId,
                drainingIds.size(),
                active.size() - drainingIds.size(),
                event.shutdownAfterDrain(),
                timeoutSeconds);

        // If no instances needed draining (all were empty), check completion
        if (drainingIds.isEmpty()) {
            drainReconciler.checkDrainCompletion(nodeId);
        }
    }

    private void queueTransfersForInstance(InstanceInfo instance, String nodeId, String kickMessage) {
        var players = clusterState.getAllPlayers().stream()
                .filter(p -> p.instanceId().equals(instance.id()))
                .toList();

        for (var player : players) {
            var target = findTransferTarget(instance.group(), nodeId);
            if (target != null) {
                workflowStateStore.queueTransfer(player.uuid(), target.id());
                logger.debug("Queued transfer for player {} from {} to {}", player.name(), instance.id(), target.id());
            } else {
                logger.debug(
                        "No transfer target for player {} on {} — player will disconnect when server stops",
                        player.name(),
                        instance.id());
            }
        }
    }

    private InstanceInfo findTransferTarget(String group, String drainingNodeId) {
        // Try same group first — RUNNING instances on non-draining nodes, lowest player
        // count
        var target = clusterState.getInstancesByGroup(group).stream()
                .filter(i -> i.state() == InstanceState.RUNNING)
                .filter(i -> !i.nodeId().equals(drainingNodeId))
                .filter(i -> !isDraining(i.nodeId()))
                .min(Comparator.comparingInt(InstanceInfo::playerCount))
                .orElse(null);

        if (target != null) return target;

        // Try fallback group
        var groupConfig = groupManager.get(group).orElse(null);
        if (groupConfig != null
                && groupConfig.fallbackGroup() != null
                && !groupConfig.fallbackGroup().isBlank()) {
            target = clusterState.getInstancesByGroup(groupConfig.fallbackGroup()).stream()
                    .filter(i -> i.state() == InstanceState.RUNNING)
                    .filter(i -> !isDraining(i.nodeId()))
                    .min(Comparator.comparingInt(InstanceInfo::playerCount))
                    .orElse(null);
        }

        return target;
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        if (!leadership.isLeader()) {
            return;
        }
        String instanceId = event.instanceId();

        // Find which draining node owns this instance
        for (var entry : workflowStateStore.nodeDrains().entrySet()) {
            NodeDrainIntent intent = entry.getValue();
            if (!intent.drainingInstanceIds().contains(instanceId)) continue;

            // Check if this instance now has no players
            long remaining = clusterState.getAllPlayers().stream()
                    .filter(p -> p.instanceId().equals(instanceId))
                    .count();

            if (remaining == 0) {
                drainReconciler.handleDrainingInstanceWithoutPlayers(entry.getKey(), instanceId);
            }
            break;
        }
    }

    private void onInstanceStateChanged(InstanceStateChangedEvent event) {
        if (!leadership.isLeader()) {
            return;
        }
        if (!EVENT_TERMINAL_STATES.contains(event.newState())) return;

        String nodeId = event.nodeId();
        if (workflowStateStore.getNodeDrain(nodeId).isEmpty()) return;

        drainReconciler.checkDrainCompletion(nodeId);
    }

    /**
     * Returns true if the given node is currently being drained.
     */
    public boolean isDraining(String nodeId) {
        return workflowStateStore.getNodeDrain(nodeId).isPresent();
    }

    public void reconcilePersistedDrains() {
        drainReconciler.reconcilePersistedDrains();
    }

    /**
     * Shut down the timeout executor. Call from controller shutdown.
     */
    public void shutdown() {
        if (reconcileTask != null) {
            reconcileTask.cancel(false);
        }
        drainTimeouts.values().forEach(timeout -> timeout.cancel(false));
        drainTimeouts.clear();
        timeoutExecutor.shutdownNow();
    }

    private void scheduleDrainTimeout(String nodeId, Instant timeoutAt) {
        var existing = drainTimeouts.remove(nodeId);
        if (existing != null) existing.cancel(false);
        long delayMillis = Math.max(0, timeoutAt.toEpochMilli() - Instant.now().toEpochMilli());
        var timeout = timeoutExecutor.schedule(
                () -> {
                    if (leadership.isLeader()) {
                        drainReconciler.onDrainTimeout(nodeId);
                    }
                },
                delayMillis,
                TimeUnit.MILLISECONDS);
        drainTimeouts.put(nodeId, timeout);
    }

    private void reconcilePersistedDrainsSafely() {
        if (!leadership.isLeader()) {
            return;
        }
        try {
            reconcilePersistedDrains();
        } catch (Exception e) {
            logger.warn("Failed to reconcile persisted drains: {}", e.getMessage());
        }
    }

    private void cancelDrainTimeout(String nodeId) {
        var timeout = drainTimeouts.remove(nodeId);
        if (timeout != null) {
            timeout.cancel(false);
        }
    }
}
