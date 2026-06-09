package me.prexorjustin.prexorcloud.controller.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceTransitionValidator;
import me.prexorjustin.prexorcloud.controller.state.StartRetryIntent;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the start-retry loop for the Scheduler.
 *
 * <p>When a {@link StartRetryIntent} is registered (transient start
 * failure on a daemon, capped at {@link #MAX_START_RETRY_ATTEMPTS}
 * attempts), the orchestrator schedules a future wakeup, validates
 * the instance state at fire time, dispatches a fresh
 * {@code StartInstance} message, and either succeeds, transitions to
 * a permanent failure, or reschedules another retry.
 *
 * <p>Two storage modes are supported:
 *
 * <ul>
 *   <li><b>Single-controller</b> (no Redis/Valkey): wakeups are scheduled
 *       in-process via {@link ScheduledExecutorService}. The
 *       {@link #pendingStartRetryTasks} map tracks future handles so a
 *       state change can cancel the pending retry.
 *   <li><b>Active-active</b> (Redis present): wakeups go through
 *       {@link StartRetryWakeupQueue} which is durable across controller
 *       failover. The in-memory map is unused in this mode.
 * </ul>
 *
 * <p>This class extracted from {@link Scheduler}. The Scheduler keeps a
 * thin {@code retryStart}/{@code clearStartRetryBudget}/{@code
 * reconcilePersistedStartRetries}/{@code processDueStartRetryWakeups}
 * delegator API for backwards compatibility with REST + tests.
 */
public final class StartRetryOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(StartRetryOrchestrator.class);

    static final int MAX_START_RETRY_ATTEMPTS = 3;

    private final WorkflowStateStore workflowStateStore;
    private final ClusterState clusterState;
    private final StateStore stateStore;
    private final GroupManager groupManager;
    private final InstancePlacementCoordinator placementCoordinator;
    private final LeaseGate leaseGate;
    private final DistributedLeaseManager leaseManager; // nullable
    private final StartRetryWakeupQueue startRetryWakeupQueue; // nullable
    private final Supplier<ScheduledExecutorService> executorSupplier;

    /**
     * In-memory book-keeping of scheduled retry futures, keyed by instance
     * id. Used in single-controller (no Redis) mode; in active-active mode
     * {@link #startRetryWakeupQueue} is the durable peer.
     */
    private final Map<String, ScheduledFuture<?>> pendingStartRetryTasks = new ConcurrentHashMap<>();

    public StartRetryOrchestrator(
            WorkflowStateStore workflowStateStore,
            ClusterState clusterState,
            StateStore stateStore,
            GroupManager groupManager,
            InstancePlacementCoordinator placementCoordinator,
            LeaseGate leaseGate,
            DistributedLeaseManager leaseManager,
            StartRetryWakeupQueue startRetryWakeupQueue,
            Supplier<ScheduledExecutorService> executorSupplier) {
        this.workflowStateStore = workflowStateStore;
        this.clusterState = clusterState;
        this.stateStore = stateStore;
        this.groupManager = groupManager;
        this.placementCoordinator = placementCoordinator;
        this.leaseGate = leaseGate;
        this.leaseManager = leaseManager;
        this.startRetryWakeupQueue = startRetryWakeupQueue;
        this.executorSupplier = executorSupplier;
    }

    // ──────────────────────────────────────────────────────────────────
    // Public entry points (mirror the Scheduler's previous public API)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Register a transient start-failure retry. Returns false (and clears
     * any pending budget) when the request is unrecoverable: instance is
     * gone, instance is in a terminal state, composition plan is missing,
     * or the retry budget is exhausted.
     */
    public boolean retry(String instanceId, int retryAfterSeconds, String reason) {
        var instance = clusterState.getInstance(instanceId).orElse(null);
        if (instance == null) {
            clearBudget(instanceId);
            logger.warn("Cannot retry start for {}: instance not found", instanceId);
            return false;
        }
        if (InstanceTransitionValidator.isTerminal(instance.state())) {
            clearBudget(instanceId);
            logger.debug("Skipping start retry for {}: instance already terminal ({})", instanceId, instance.state());
            return false;
        }
        var compositionPlan = stateStore.getInstanceCompositionPlan(instanceId).orElse(null);
        if (compositionPlan == null) {
            clearBudget(instanceId);
            logger.warn("Cannot retry start for {}: composition plan missing", instanceId);
            return false;
        }

        int attempt = workflowStateStore
                        .getStartRetry(instanceId)
                        .map(StartRetryIntent::attempt)
                        .orElse(0)
                + 1;
        if (attempt > MAX_START_RETRY_ATTEMPTS) {
            clearBudget(instanceId);
            logger.warn(
                    "Rejecting transient start retry for {} after {} attempts (reason={})",
                    instanceId,
                    MAX_START_RETRY_ATTEMPTS,
                    reason);
            return false;
        }

        var intent = new StartRetryIntent(
                instanceId,
                instance.group(),
                instance.nodeId(),
                reason,
                compositionPlan.planHash(),
                attempt,
                Instant.now().plusSeconds(Math.max(1, retryAfterSeconds)),
                Instant.now());
        workflowStateStore.saveStartRetry(intent);
        scheduleRetry(intent);
        return true;
    }

    /**
     * Cancel any pending retry task for {@code instanceId} and delete the
     * persisted intent. Idempotent. Called from many places: when a retry
     * succeeds, when the instance moves to a terminal state, when the
     * instance ID disappears from the cluster, or when retry attempts are
     * exhausted.
     */
    public void clearBudget(String instanceId) {
        var pending = pendingStartRetryTasks.remove(instanceId);
        if (pending != null) {
            pending.cancel(false);
        }
        if (startRetryWakeupQueue != null) {
            startRetryWakeupQueue.cancel(instanceId);
        }
        workflowStateStore.deleteStartRetry(instanceId);
    }

    /**
     * Re-issue every persisted retry intent (called once at controller
     * startup so retries that were in flight when the previous leader died
     * resume on the new leader).
     */
    public void reconcilePersisted() {
        for (var intent : workflowStateStore.startRetries().values()) {
            reconcileOne(intent);
        }
    }

    /**
     * Re-issue persisted retries scoped to one group — called from the
     * lease-acquired hook so newly-acquired leases pick up in-flight
     * retries for groups they're now responsible for.
     */
    public void reconcilePersistedForGroup(String groupName) {
        for (var intent : workflowStateStore.startRetries().values()) {
            if (intent.groupName().equals(groupName)) {
                reconcileOne(intent);
            }
        }
    }

    /**
     * Drain the wakeup queue (Redis-backed mode only) — pulls all due
     * intents and dispatches a retry for each. No-op when no queue is
     * configured.
     */
    public void processDueWakeupsSafely() {
        try {
            processDueWakeups();
        } catch (Exception e) {
            logger.warn("Failed to process due start retry wakeups: {}", e.getMessage(), e);
        }
    }

    private void processDueWakeups() {
        if (startRetryWakeupQueue == null) {
            return;
        }
        for (String instanceId : startRetryWakeupQueue.claimDue(Instant.now(), 64)) {
            var intent = workflowStateStore.getStartRetry(instanceId).orElse(null);
            if (intent == null) {
                startRetryWakeupQueue.cancel(instanceId);
                continue;
            }
            if (!leaseGate.ownsGroupLease(intent.groupName())) {
                startRetryWakeupQueue.schedule(intent);
                continue;
            }
            resendStart(intent);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────

    private void reconcileOne(StartRetryIntent intent) {
        var instance = clusterState.getInstance(intent.instanceId()).orElse(null);
        if (instance == null || InstanceTransitionValidator.isTerminal(instance.state())) {
            clearBudget(intent.instanceId());
            return;
        }
        if (!leaseGate.ownsGroupLease(intent.groupName())) {
            return;
        }
        scheduleRetry(intent);
    }

    private void scheduleRetry(StartRetryIntent intent) {
        if (!leaseGate.ownsGroupLease(intent.groupName())) {
            return;
        }
        if (startRetryWakeupQueue != null) {
            startRetryWakeupQueue.schedule(intent);
            return;
        }
        var pending = pendingStartRetryTasks.get(intent.instanceId());
        if (pending != null && !pending.isDone()) {
            return;
        }
        var existing = pendingStartRetryTasks.remove(intent.instanceId());
        if (existing != null) {
            existing.cancel(false);
        }
        long delayMillis =
                Math.max(0, Duration.between(Instant.now(), intent.retryAt()).toMillis());
        Runnable retryTask = () -> resendStart(intent);
        var executor = executorSupplier.get();
        if (executor != null) {
            var future = executor.schedule(retryTask, delayMillis, TimeUnit.MILLISECONDS);
            pendingStartRetryTasks.put(intent.instanceId(), future);
        } else {
            // Pre-start state: no executor yet. Spin up a virtual thread
            // so the retry isn't dropped — happens during controller boot
            // when persisted intents are reconciled before start().
            Thread.startVirtualThread(retryTask);
        }
    }

    private void resendStart(StartRetryIntent intent) {
        if (startRetryWakeupQueue == null) {
            pendingStartRetryTasks.remove(intent.instanceId());
        }
        String instanceId = intent.instanceId();
        DistributedLeaseManager.Lease lease = leaseGate.acquireGroupLease(intent.groupName());
        if (leaseManager != null && lease == null) {
            logger.debug(
                    "Skipping persisted start retry for {} because this controller does not hold lease for group {}",
                    instanceId,
                    intent.groupName());
            return;
        }
        var current = clusterState.getInstance(instanceId).orElse(null);
        if (current == null) {
            clearBudget(instanceId);
            return;
        }
        if (current.state() == InstanceState.RUNNING
                || current.state() == InstanceState.STOPPING
                || current.state() == InstanceState.STOPPED
                || current.state() == InstanceState.CRASHED) {
            clearBudget(instanceId);
            logger.debug("Skipping start retry for {}: instance moved to {}", instanceId, current.state());
            return;
        }

        var group = groupManager.get(current.group()).orElse(null);
        if (group == null) {
            clearBudget(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.CRASHED);
            logger.warn("Cannot retry start for {}: group {} no longer exists", instanceId, current.group());
            return;
        }

        var compositionPlan = stateStore.getInstanceCompositionPlan(instanceId).orElse(null);
        if (compositionPlan == null) {
            clearBudget(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.CRASHED);
            logger.warn("Cannot retry start for {}: composition plan missing during resend", instanceId);
            return;
        }
        var resolved = groupManager.resolveGroup(group.name());
        var startMsg = placementCoordinator.buildStartMessage(
                resolved, current, compositionPlan, clusterState.issuePluginToken(instanceId));
        if (!leaseGate.ensureLeaseCurrent(lease, intent.groupName(), "retry start for instance " + instanceId)) {
            return;
        }
        if (!placementCoordinator.dispatchStartMessage(current.nodeId(), instanceId, startMsg)) {
            clearBudget(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.CRASHED);
            logger.warn(
                    "Transient start retry {}/{} for {} failed to dispatch to node {}",
                    intent.attempt(),
                    MAX_START_RETRY_ATTEMPTS,
                    instanceId,
                    current.nodeId());
            return;
        }

        logger.info(
                "Retrying StartInstance for {} on node {} (attempt {}/{}, reason={})",
                instanceId,
                current.nodeId(),
                intent.attempt(),
                MAX_START_RETRY_ATTEMPTS,
                intent.reason());
    }
}
