package me.prexorjustin.prexorcloud.controller.scheduler;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns recovery dispatch for instances stuck in non-terminal start states
 * after a controller restart, node reconnect, or lease handover.
 *
 * <p>"Recoverable" here means the cluster believes an instance was being
 * brought up — {@code SCHEDULED}, {@code PREPARING}, or {@code STARTING} —
 * but no {@link me.prexorjustin.prexorcloud.controller.state.StartRetryIntent}
 * is recorded for it. That happens when the leader died mid-start or a
 * daemon reconnected with the instance still mid-flight. The orchestrator
 * re-issues the {@code StartInstance} message using the persisted
 * composition plan and applies an in-memory backoff so the dispatch
 * isn't retried every scheduler tick.
 *
 * <p>Distinct from {@link StartRetryOrchestrator}: that one handles
 * transient prep failures with a persisted intent and bounded attempts.
 * Recovery has no persisted intent — it's driven by the instance's own
 * state in the cluster snapshot.
 *
 * <p>Backoff is intentionally in-memory: it's only a throttle on
 * scheduler tick frequency, and a controller restart should rediscover
 * the instance and try again immediately.
 */
public final class RecoveryOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(RecoveryOrchestrator.class);

    private final ClusterState clusterState;
    private final WorkflowStateStore workflowStateStore;
    private final StateStore stateStore;
    private final GroupManager groupManager;
    private final InstancePlacementCoordinator placementCoordinator;
    private final LeaseGate leaseGate;
    private final DistributedLeaseManager leaseManager; // nullable
    private final long evaluationIntervalSeconds;

    /**
     * In-memory backoff timestamps, keyed by instance id. Bounded by
     * the cluster's live instance set — entries are cleared when an
     * instance leaves a recoverable state.
     */
    private final Map<String, Instant> pendingStartRecoveryBackoffUntil = new ConcurrentHashMap<>();

    public RecoveryOrchestrator(
            ClusterState clusterState,
            WorkflowStateStore workflowStateStore,
            StateStore stateStore,
            GroupManager groupManager,
            InstancePlacementCoordinator placementCoordinator,
            LeaseGate leaseGate,
            DistributedLeaseManager leaseManager,
            long evaluationIntervalSeconds) {
        this.clusterState = clusterState;
        this.workflowStateStore = workflowStateStore;
        this.stateStore = stateStore;
        this.groupManager = groupManager;
        this.placementCoordinator = placementCoordinator;
        this.leaseGate = leaseGate;
        this.leaseManager = leaseManager;
        this.evaluationIntervalSeconds = evaluationIntervalSeconds;
    }

    // ──────────────────────────────────────────────────────────────────
    // Public entry points (mirror the Scheduler's previous public API)
    // ──────────────────────────────────────────────────────────────────

    /** Sweep every instance in the cluster for a recoverable start. */
    public void reconcileAll() {
        for (var instance : clusterState.getAllInstances()) {
            reconcileOne(instance);
        }
    }

    /** Sweep only instances bound to {@code nodeId} — called on node reconnect. */
    public void reconcileForNode(String nodeId) {
        for (var instance : clusterState.getInstancesByNode(nodeId)) {
            reconcileOne(instance);
        }
    }

    /** Sweep instances in a single group — called from the lease-acquired hook. */
    public void reconcileForGroup(String groupName) {
        for (var instance : clusterState.getInstancesByGroup(groupName)) {
            reconcileOne(instance);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────────────────

    private void reconcileOne(InstanceInfo instance) {
        if (!isRecoverableStartState(instance.state())) {
            pendingStartRecoveryBackoffUntil.remove(instance.id());
            return;
        }
        if (workflowStateStore.getStartRetry(instance.id()).isPresent()) {
            return;
        }
        if (!leaseGate.ownsGroupLease(instance.group())) {
            return;
        }
        Instant retryAfter = pendingStartRecoveryBackoffUntil.get(instance.id());
        if (retryAfter != null && retryAfter.isAfter(Instant.now())) {
            return;
        }

        var group = groupManager.get(instance.group()).orElse(null);
        if (group == null) {
            return;
        }
        var compositionPlan =
                stateStore.getInstanceCompositionPlan(instance.id()).orElse(null);
        if (compositionPlan == null) {
            return;
        }

        var resolved = groupManager.resolveGroup(group.name());
        DistributedLeaseManager.Lease lease = leaseGate.acquireGroupLease(instance.group());
        if (leaseManager != null && lease == null) {
            return;
        }
        var startMsg = placementCoordinator.buildStartMessage(
                resolved, instance, compositionPlan, clusterState.issuePluginToken(instance.id()));
        if (!leaseGate.ensureLeaseCurrent(
                lease, instance.group(), "recover start dispatch for instance " + instance.id())) {
            return;
        }
        if (!placementCoordinator.dispatchStartMessage(instance.nodeId(), instance.id(), startMsg)) {
            return;
        }

        pendingStartRecoveryBackoffUntil.put(
                instance.id(), Instant.now().plusSeconds(Math.max(1L, evaluationIntervalSeconds)));
        logger.info(
                "Recovered persisted start dispatch for {} on node {} (state={}, planHash={})",
                instance.id(),
                instance.nodeId(),
                instance.state(),
                compositionPlan.planHash());
    }

    private static boolean isRecoverableStartState(InstanceState state) {
        return state == InstanceState.SCHEDULED || state == InstanceState.PREPARING || state == InstanceState.STARTING;
    }
}
