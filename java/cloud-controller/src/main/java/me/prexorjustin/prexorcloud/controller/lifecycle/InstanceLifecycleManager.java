package me.prexorjustin.prexorcloud.controller.lifecycle;

import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.api.event.events.InstanceStateChangedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeDisconnectedEvent;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reacts to instance state transitions. Handles:
 * <ul>
 * <li>Removing STOPPED/CRASHED instances from ClusterState after a delay</li>
 * <li>Releasing ports from node state when instances terminate</li>
 * <li>Marking instances as CRASHED when their node disconnects</li>
 * </ul>
 */
public final class InstanceLifecycleManager {

    private static final Logger logger = LoggerFactory.getLogger(InstanceLifecycleManager.class);

    private static final long STOPPED_CLEANUP_DELAY_SECONDS = 60;
    private static final long CRASHED_CLEANUP_DELAY_SECONDS = 300;
    private static final long HEALING_RECONCILE_INTERVAL_SECONDS = 5;

    private final ClusterState clusterState;
    private final GroupManager groupManager;
    private final ConsoleBuffer consoleBuffer;
    private final ScheduledExecutorService cleanupExecutor;
    private final HealingReconciler healingReconciler;
    private volatile ScheduledFuture<?> healingReconcileTask;

    public InstanceLifecycleManager(
            ClusterState clusterState, EventBus eventBus, GroupManager groupManager, ConsoleBuffer consoleBuffer) {
        this.clusterState = clusterState;
        this.groupManager = groupManager;
        this.consoleBuffer = consoleBuffer;
        this.healingReconciler = new HealingReconciler(clusterState);
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "instance-lifecycle");
            t.setDaemon(true);
            return t;
        });

        eventBus.subscribe(InstanceStateChangedEvent.class, this::onInstanceStateChanged);
        eventBus.subscribe(NodeDisconnectedEvent.class, this::onNodeDisconnected);

        sweepHydratedTerminalInstances();

        logger.debug("InstanceLifecycleManager initialized");
    }

    /**
     * Re-queue cleanup for instances that were already STOPPED/CRASHED when this
     * controller started. Terminal-state removal is normally scheduled from the
     * {@link InstanceStateChangedEvent} that fires on the transition, but a
     * controller restart (or HA leader failover) hydrates such instances from the
     * snapshot WITHOUT firing that event -- and the previous controller's in-memory
     * removal timers were lost. Without this sweep they would linger forever and
     * accumulate on every restart. The scheduled task re-checks the state at fire
     * time, so an instance that the daemon re-reports as RUNNING on reconnect is
     * left alone.
     */
    private void sweepHydratedTerminalInstances() {
        int requeued = 0;
        for (InstanceInfo instance : clusterState.getAllInstances()) {
            if (instance.state() == me.prexorjustin.prexorcloud.protocol.InstanceState.STOPPED) {
                scheduleRemoval(instance.id(), STOPPED_CLEANUP_DELAY_SECONDS);
                requeued++;
            } else if (instance.state() == me.prexorjustin.prexorcloud.protocol.InstanceState.CRASHED) {
                scheduleRemoval(instance.id(), CRASHED_CLEANUP_DELAY_SECONDS);
                requeued++;
            }
        }
        if (requeued > 0) {
            logger.info("Re-queued cleanup for {} hydrated terminal instance(s) after startup", requeued);
        }
    }

    private void onInstanceStateChanged(InstanceStateChangedEvent event) {
        if (event.newState() == InstanceState.STOPPED) {
            handleTerminalState(event.instanceId(), event.nodeId(), STOPPED_CLEANUP_DELAY_SECONDS);
            healingReconciler.clearHealingIntent(event.instanceId());
        } else if (event.newState() == InstanceState.CRASHED) {
            handleTerminalState(event.instanceId(), event.nodeId(), CRASHED_CLEANUP_DELAY_SECONDS);
            healingReconciler.queueHealing(event.instanceId(), event.group(), "INSTANCE_CRASHED");
        } else if (event.newState() == InstanceState.SCHEDULED
                || event.newState() == InstanceState.PREPARING
                || event.newState() == InstanceState.STARTING
                || event.newState() == InstanceState.RUNNING) {
            healingReconciler.clearHealingIntent(event.instanceId());
        }
    }

    private void onNodeDisconnected(NodeDisconnectedEvent event) {
        var orphaned = clusterState.getInstancesByNode(event.nodeId());
        for (InstanceInfo instance : orphaned) {
            if (instance.state() != me.prexorjustin.prexorcloud.protocol.InstanceState.STOPPED
                    && instance.state() != me.prexorjustin.prexorcloud.protocol.InstanceState.CRASHED) {
                logger.warn("Node {} disconnected -- marking instance {} as CRASHED", event.nodeId(), instance.id());
                clusterState.updateInstanceState(
                        instance.id(), me.prexorjustin.prexorcloud.protocol.InstanceState.CRASHED);
            }
        }
    }

    private void releasePort(String instanceId, String nodeId) {
        clusterState.getInstance(instanceId).ifPresent(instance -> {
            clusterState.getNode(nodeId).ifPresent(node -> {
                var updatedPorts = new HashSet<>(node.usedPorts());
                if (updatedPorts.remove(instance.port())) {
                    // Look up the group's memory allocation to release it from the node
                    long memoryToRelease = groupManager
                            .get(instance.group())
                            .map(g -> groupManager.resolveGroup(g.name()).memoryMb())
                            .orElse(0);
                    long updatedMemory = Math.max(0, node.usedMemoryMb() - memoryToRelease);

                    clusterState.updateNodeStatus(
                            nodeId,
                            node.cpuUsage(),
                            node.totalMemoryMb(),
                            updatedMemory,
                            node.freeDiskMb(),
                            node.totalDiskMb(),
                            Math.max(0, node.instanceCount() - 1),
                            updatedPorts);
                    logger.debug(
                            "Released port {} and {}MB memory from node {} (instance {})",
                            instance.port(),
                            memoryToRelease,
                            nodeId,
                            instanceId);
                }
            });
        });
    }

    private void scheduleRemoval(String instanceId, long delaySeconds) {
        cleanupExecutor.schedule(
                () -> {
                    var instance = clusterState.getInstance(instanceId);
                    if (instance.isPresent()
                            && (instance.get().state() == me.prexorjustin.prexorcloud.protocol.InstanceState.STOPPED
                                    || instance.get().state()
                                            == me.prexorjustin.prexorcloud.protocol.InstanceState.CRASHED)) {
                        clusterState.removeInstance(instanceId);
                        consoleBuffer.evict(instanceId);
                        logger.debug("Removed instance {} from cluster state", instanceId);
                    }
                },
                delaySeconds,
                TimeUnit.SECONDS);
    }

    private void handleTerminalState(String instanceId, String nodeId, long cleanupDelaySeconds) {
        consoleBuffer.evict(instanceId);
        clusterState.unregisterPluginToken(instanceId);
        releasePort(instanceId, nodeId);
        scheduleRemoval(instanceId, cleanupDelaySeconds);
    }

    public void attachHealingWorkflow(WorkflowStateStore workflowStateStore, Scheduler scheduler) {
        attachHealingWorkflow(workflowStateStore, scheduler, null);
    }

    public void attachHealingWorkflow(
            WorkflowStateStore workflowStateStore, Scheduler scheduler, DistributedLeaseManager leaseManager) {
        healingReconciler.attachWorkflow(workflowStateStore, scheduler::scheduleReplacement, leaseManager);
        if (leaseManager != null && healingReconcileTask == null) {
            healingReconcileTask = cleanupExecutor.scheduleWithFixedDelay(
                    this::reconcilePersistedHealingActionsSafely,
                    HEALING_RECONCILE_INTERVAL_SECONDS,
                    HEALING_RECONCILE_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
        }
    }

    public void reconcilePersistedHealingActions() {
        healingReconciler.reconcilePersistedHealingActions();
    }

    public void stop() {
        var task = healingReconcileTask;
        if (task != null) {
            task.cancel(false);
        }
        cleanupExecutor.shutdownNow();
    }

    private void reconcilePersistedHealingActionsSafely() {
        try {
            reconcilePersistedHealingActions();
        } catch (Exception e) {
            logger.warn("Failed to reconcile persisted healing actions: {}", e.getMessage());
        }
    }
}
