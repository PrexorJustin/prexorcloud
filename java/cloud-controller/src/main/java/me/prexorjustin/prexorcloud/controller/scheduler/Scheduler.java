package me.prexorjustin.prexorcloud.controller.scheduler;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import me.prexorjustin.prexorcloud.common.identity.InstanceIdGenerator;
import me.prexorjustin.prexorcloud.common.logging.CorrelationContext;
import me.prexorjustin.prexorcloud.controller.cluster.ConvergenceGate;
import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentReconciler;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord;
import me.prexorjustin.prexorcloud.controller.event_choreography.EventChoreographer;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main scheduling loop. Runs periodically to ensure group minimums are met,
 * evaluate dynamic scaling, and place instances on nodes. Respects scalingMode,
 * maintenance, dependsOn, and startupWeight.
 */
public final class Scheduler implements LeaseGate {

    private static final Logger logger = LoggerFactory.getLogger(Scheduler.class);

    private final GroupManager groupManager;
    private final ClusterState clusterState;
    private final ScalingEvaluator scalingEvaluator;
    private final CrashLoopDetector crashLoopDetector;
    private final StateStore stateStore;
    private final WorkflowStateStore workflowStateStore;
    private final InstancePlacementCoordinator placementCoordinator;
    private final DeploymentReconciler deploymentReconciler;
    private final SchedulerDesiredStatePlanner desiredStatePlanner;
    private final NodeMessageDispatcher nodeMessageDispatcher;
    private final long evaluationIntervalSeconds;
    private final java.util.function.BooleanSupplier globalMaintenanceCheck;
    private final EventChoreographer eventChoreographer; // nullable
    private final MetricsCollector metricsCollector; // nullable
    // Tracer for the scheduler.tick span (Track D.2). Defaults to no-op; bootstrap swaps in the
    // real tracer when telemetry is enabled, so tests and telemetry-off deployments cost nothing.
    private io.opentelemetry.api.trace.Tracer tracer =
            io.opentelemetry.api.OpenTelemetry.noop().getTracer("prexorcloud-controller");
    private final StartRetryOrchestrator startRetry;
    private final RecoveryOrchestrator recovery;
    private final Set<String> activeDeployments = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Single-writer authority (Phase 2): the leader is the sole controller that ticks, places,
    // recovers, and reconciles deployments. Defaults to always-leader so single-controller installs
    // and tests behave unchanged; bootstrap injects the real MongoLeaderElector.
    private volatile Leadership leadership = Leadership.alwaysLeader();
    // Observation-phase gate: on leadership acquisition, defer scale-reconcile until daemons report
    // (or grace expires) so a fresh leader can't spawn duplicates of instances it hasn't seen yet.
    // Null in tests / single-controller installs that never change leadership — treated as open.
    private volatile ConvergenceGate convergenceGate;

    private ScheduledExecutorService executor;

    // Phase 5: coalescing latch for the reactive change-stream layer. A burst of change events
    // collapses into at most one queued out-of-band tick (the single scheduler thread serialises
    // it with the periodic ticks anyway). Reset at the start of the queued task, so a change
    // arriving while a reconcile runs still queues a follow-up.
    private final java.util.concurrent.atomic.AtomicBoolean reconcileQueued =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public Scheduler(
            GroupManager groupManager,
            ClusterState clusterState,
            ScalingEvaluator scalingEvaluator,
            CrashLoopDetector crashLoopDetector,
            StateStore stateStore,
            WorkflowStateStore workflowStateStore,
            InstancePlacementCoordinator placementCoordinator,
            DeploymentReconciler deploymentReconciler,
            long evaluationIntervalSeconds,
            java.util.function.BooleanSupplier globalMaintenanceCheck,
            NodeMessageDispatcher nodeMessageDispatcher) {
        this(
                groupManager,
                clusterState,
                scalingEvaluator,
                crashLoopDetector,
                stateStore,
                workflowStateStore,
                placementCoordinator,
                deploymentReconciler,
                evaluationIntervalSeconds,
                globalMaintenanceCheck,
                nodeMessageDispatcher,
                null,
                null);
    }

    public Scheduler(
            GroupManager groupManager,
            ClusterState clusterState,
            ScalingEvaluator scalingEvaluator,
            CrashLoopDetector crashLoopDetector,
            StateStore stateStore,
            WorkflowStateStore workflowStateStore,
            InstancePlacementCoordinator placementCoordinator,
            DeploymentReconciler deploymentReconciler,
            long evaluationIntervalSeconds,
            java.util.function.BooleanSupplier globalMaintenanceCheck,
            NodeMessageDispatcher nodeMessageDispatcher,
            EventChoreographer eventChoreographer) {
        this(
                groupManager,
                clusterState,
                scalingEvaluator,
                crashLoopDetector,
                stateStore,
                workflowStateStore,
                placementCoordinator,
                deploymentReconciler,
                evaluationIntervalSeconds,
                globalMaintenanceCheck,
                nodeMessageDispatcher,
                eventChoreographer,
                null);
    }

    public Scheduler(
            GroupManager groupManager,
            ClusterState clusterState,
            ScalingEvaluator scalingEvaluator,
            CrashLoopDetector crashLoopDetector,
            StateStore stateStore,
            WorkflowStateStore workflowStateStore,
            InstancePlacementCoordinator placementCoordinator,
            DeploymentReconciler deploymentReconciler,
            long evaluationIntervalSeconds,
            java.util.function.BooleanSupplier globalMaintenanceCheck,
            NodeMessageDispatcher nodeMessageDispatcher,
            EventChoreographer eventChoreographer,
            MetricsCollector metricsCollector) {
        this.groupManager = groupManager;
        this.clusterState = clusterState;
        this.scalingEvaluator = scalingEvaluator;
        this.crashLoopDetector = crashLoopDetector;
        this.stateStore = stateStore;
        this.workflowStateStore = workflowStateStore;
        this.placementCoordinator = placementCoordinator;
        this.deploymentReconciler = deploymentReconciler;
        this.desiredStatePlanner = new SchedulerDesiredStatePlanner(
                groupManager,
                clusterState,
                scalingEvaluator,
                crashLoopDetector,
                globalMaintenanceCheck,
                eventChoreographer,
                Instant::now);
        this.nodeMessageDispatcher = nodeMessageDispatcher;
        this.evaluationIntervalSeconds = evaluationIntervalSeconds;
        this.globalMaintenanceCheck = globalMaintenanceCheck;
        this.eventChoreographer = eventChoreographer;
        this.metricsCollector = metricsCollector;
        this.startRetry = new StartRetryOrchestrator(
                workflowStateStore,
                clusterState,
                stateStore,
                groupManager,
                placementCoordinator,
                this,
                () -> this.executor);
        this.recovery = new RecoveryOrchestrator(
                clusterState,
                workflowStateStore,
                stateStore,
                groupManager,
                placementCoordinator,
                evaluationIntervalSeconds);
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(
                this::evaluate, evaluationIntervalSeconds, evaluationIntervalSeconds, TimeUnit.SECONDS);
        logger.debug("Scheduler started (interval={}s)", evaluationIntervalSeconds);
    }

    /**
     * Request an out-of-band reconcile tick (Phase 5 change-stream layer). Runs {@link #evaluate()}
     * on the single scheduler thread, so it serialises with the periodic ticks and is never
     * concurrent with one. Coalesces a burst of change events into at most one queued tick, and is a
     * no-op before {@link #start()} / after {@link #stop()}. The periodic tick remains the
     * correctness floor — this only reduces reaction latency, it can never be the sole driver.
     */
    public void requestReconcile() {
        ScheduledExecutorService ex = executor;
        if (ex == null) {
            return;
        }
        if (!reconcileQueued.compareAndSet(false, true)) {
            return; // a reconcile is already queued and not yet started
        }
        try {
            ex.execute(() -> {
                reconcileQueued.set(false);
                evaluate();
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            reconcileQueued.set(false); // executor shutting down — periodic floor (if any) still covers it
        }
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public InstancePlacementCoordinator placementCoordinator() {
        return placementCoordinator;
    }

    /** Swap in the real OpenTelemetry tracer (Track D.2). Null restores the no-op default. */
    public void setTracer(io.opentelemetry.api.trace.Tracer tracer) {
        this.tracer = tracer != null
                ? tracer
                : io.opentelemetry.api.OpenTelemetry.noop().getTracer("prexorcloud-controller");
    }

    /**
     * Evaluate all groups in dependency order using topological sort. Groups within
     * the same dependency tier are evaluated concurrently via
     * {@link StructuredTaskScope} (JEP 505), sorted by startupWeight.
     */
    private void evaluate() {
        // Single-writer: only the leader runs the scheduler. Followers do nothing — this is the
        // "ownership = leadership" gate that collapses the cross-controller divergence bug class.
        if (!leadership.isLeader()) {
            return;
        }
        String schedulerRunId = CorrelationContext.newId();
        long startNanos = System.nanoTime();
        boolean success = false;
        int groupsEvaluated = 0;
        io.opentelemetry.api.trace.Span span =
                tracer.spanBuilder("scheduler.tick").startSpan();
        io.opentelemetry.context.Scope spanScope = span.makeCurrent();
        try {
            try (var ignored = CorrelationContext.open("schedulerRunId", schedulerRunId)) {
                if (eventChoreographer != null) {
                    eventChoreographer.refresh(Instant.now());
                }
                reconcileRecoverableStarts();
                reconcilePersistedStartRetries();
                reconcilePersistedDeployments();

                // Convergence gate: right after a leadership change, don't scale-reconcile
                // (spawn / scale-down / reschedule) until daemons have reported their inventory,
                // or we'd spawn duplicates of instances we haven't observed yet. State-learning
                // and idempotent recovery above are unaffected — only scaling is deferred.
                ConvergenceGate gate = convergenceGate;
                if (gate != null && !gate.canScaleReconcile()) {
                    // DEBUG, not INFO: fires every scheduler tick for the whole observation window
                    // (~15s) on each leadership change. The one-shot ConvergenceGate INFO at takeover
                    // plus the prexorcloud.convergence.observing gauge already cover this.
                    logger.debug("Scheduler: in convergence observation phase — deferring scale-reconcile this tick");
                    success = true;
                } else {
                    var tiers = desiredStatePlanner.planEvaluationOrder(groupManager.getAll());

                    for (List<GroupConfig> tier : tiers) {
                        try (var scope = StructuredTaskScope.open()) {
                            for (GroupConfig group : tier) {
                                groupsEvaluated++;
                                scope.fork(() -> {
                                    try (var groupScope = CorrelationContext.open(
                                            Map.of("schedulerRunId", schedulerRunId, "groupName", group.name()))) {
                                        evaluateGroup(group);
                                    } catch (Exception e) {
                                        logger.warn("Failed to evaluate group {}: {}", group.name(), e.getMessage(), e);
                                    }
                                    return null;
                                });
                            }
                            scope.join();
                        }
                    }
                    success = true;
                }
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Scheduler evaluation failed: {}", e.getMessage(), e);
            span.recordException(e);
        } finally {
            spanScope.close();
            span.setAttribute("scheduler.groups_evaluated", (long) groupsEvaluated);
            span.setStatus(
                    success ? io.opentelemetry.api.trace.StatusCode.OK : io.opentelemetry.api.trace.StatusCode.ERROR);
            span.end();
            if (metricsCollector != null) {
                metricsCollector.recordSchedulerTick(
                        java.time.Duration.ofNanos(System.nanoTime() - startNanos), success, groupsEvaluated);
            }
        }
    }

    private void evaluateGroup(GroupConfig group) {
        var plan = desiredStatePlanner.planGroup(group);
        if (plan.skipped()) {
            logger.debug("Skipping group {} ({})", group.name(), plan.skipReason());
            return;
        }

        executeGroupPlan(plan);
    }

    private void executeGroupPlan(SchedulerDesiredStatePlanner.GroupPlan plan) {
        GroupConfig resolved = plan.resolvedGroup();
        if (!plan.staticInstanceIdsToPlace().isEmpty()) {
            for (String instanceId : plan.staticInstanceIdsToPlace()) {
                if (!placementCoordinator.placeResolvedInstance(
                        resolved, instanceId, this::ensureLeaseCurrent, this::clearStartRetryBudget)) {
                    logger.warn("No eligible node for static instance {}", instanceId);
                    break;
                }
            }
            return;
        }

        for (int i = 0; i < plan.dynamicPlacementsToAdd(); i++) {
            // Prefer promoting a pre-warmed instance (instant capacity) over a cold start.
            if (clusterState.promoteWarmInstance(resolved.name()).isPresent()) {
                logger.info("Group {}: promoted a warm instance to serving (no cold start)", resolved.name());
                continue;
            }
            if (!placeResolvedInstance(resolved)) {
                logger.warn(
                        "No eligible node for group {} ({} of {} placed)",
                        resolved.name(),
                        i,
                        plan.dynamicPlacementsToAdd());
                break;
            }
        }

        if (plan.scaleDownInstanceId() != null) {
            stopInstance(plan.scaleDownInstanceId(), false);
            scalingEvaluator.recordScaleAction(resolved.name());
            logger.info("Scaling down group {}: stopping {}", resolved.name(), plan.scaleDownInstanceId());
        }

        // Keep the warm pool topped up to its target (promotions above may have drawn from it).
        if (plan.warmPoolTarget() > 0) {
            long warmNow = clusterState.warmInstanceCount(resolved.name());
            for (long i = warmNow; i < plan.warmPoolTarget(); i++) {
                if (!placeWarmInstance(resolved)) {
                    logger.warn("No eligible node to refill warm pool for group {}", resolved.name());
                    break;
                }
            }
        }
    }

    /**
     * Place a new dynamic instance for the given group, using gap-filling ID
     * generation.
     */
    public boolean placeInstance(GroupConfig group) {
        return placeInstance(group, Map.of());
    }

    /** As above, with per-instance variable overrides applied to the placed instance. */
    public boolean placeInstance(GroupConfig group, Map<String, String> variableOverrides) {
        return placeResolvedInstance(groupManager.resolveGroup(group.name()), variableOverrides);
    }

    private boolean placeResolvedInstance(GroupConfig resolved) {
        return placeResolvedInstance(resolved, Map.of());
    }

    private boolean placeResolvedInstance(GroupConfig resolved, Map<String, String> variableOverrides) {
        Set<String> existingIds = new HashSet<>(
                clusterState.getAllInstances().stream().map(InstanceInfo::id).toList());
        String instanceId = InstanceIdGenerator.generateDynamic(resolved.name(), existingIds);

        return placementCoordinator.placeResolvedInstance(
                resolved, instanceId, variableOverrides, this::ensureLeaseCurrent, this::clearStartRetryBudget);
    }

    /** Place a new instance and hold it in the warm pool (non-serving until promoted). */
    private boolean placeWarmInstance(GroupConfig resolved) {
        Set<String> existingIds = new HashSet<>(
                clusterState.getAllInstances().stream().map(InstanceInfo::id).toList());
        String instanceId = InstanceIdGenerator.generateDynamic(resolved.name(), existingIds);

        boolean placed = placementCoordinator.placeResolvedInstance(
                resolved, instanceId, this::ensureLeaseCurrent, this::clearStartRetryBudget);
        if (placed) {
            clusterState.markInstanceWarm(instanceId);
        }
        return placed;
    }

    /**
     * Public entry point — register a transient start-failure retry. Thin
     * delegator to {@link StartRetryOrchestrator#retry(String, int, String)}.
     */
    public boolean retryStart(String instanceId, int retryAfterSeconds, String reason) {
        return startRetry.retry(instanceId, retryAfterSeconds, reason);
    }

    /**
     * Public entry point — kept here so external callers (REST, controller
     * startup, tests) don't need to know about the orchestrator. Thin
     * delegator to {@link StartRetryOrchestrator#clearBudget(String)}.
     */
    public void clearStartRetryBudget(String instanceId) {
        startRetry.clearBudget(instanceId);
    }

    public void reconcilePersistedStartRetries() {
        startRetry.reconcilePersisted();
    }

    public void reconcilePersistedDeployments() {
        // Single-writer: only the leader reconciles deployments. Replaces the Raft "deployment-reconciler"
        // lease — under one writer there is no cross-controller race to guard. Reached both from the
        // leader's tick (already leader-gated) and from the startup durable-workflow reconcile, so the
        // guard lives here.
        if (!leadership.isLeader()) {
            return;
        }
        reconcilePersistedDeploymentsBody();
    }

    private void reconcilePersistedDeploymentsBody() {
        for (var deployment : stateStore.getDeploymentsByState("IN_PROGRESS", 50)) {
            reconcilePersistedDeployment(deployment);
        }
    }

    /**
     * Inject single-writer leadership (bootstrap, after the lease elector is up). Tests skip it and
     * run as always-leader. Propagates to the recovery orchestrator so its gate matches the tick's.
     */
    public void setLeadership(Leadership leadership) {
        this.leadership = leadership;
        this.recovery.setLeadership(leadership);
    }

    /** Inject the convergence gate so ticks just after a leadership change defer scale-reconcile. */
    public void setConvergenceGate(ConvergenceGate convergenceGate) {
        this.convergenceGate = convergenceGate;
    }

    // Thin delegators — tests + DaemonServiceImpl + evaluate() call these
    // by their original Scheduler-rooted names. Real implementation lives
    // in RecoveryOrchestrator.
    public void reconcileRecoverableStarts() {
        recovery.reconcileAll();
    }

    public void reconcileRecoverableStartsForNode(String nodeId) {
        recovery.reconcileForNode(nodeId);
    }

    /**
     * Manually schedule one new instance for a group (via REST API). For static
     * groups, places the next missing static ID. For dynamic groups, uses
     * gap-filling ID generation.
     */
    public void scheduleOne(String groupName) {
        scheduleOne(groupName, Map.of());
    }

    /** As above, with per-instance variable overrides applied to the started instance. */
    public void scheduleOne(String groupName, Map<String, String> variableOverrides) {
        var group = groupManager
                .get(groupName)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupName));
        GroupConfig resolved = groupManager.resolveGroup(groupName);

        if (resolved.isStatic() || "STATIC".equals(resolved.scalingMode())) {
            List<String> expectedIds = InstanceIdGenerator.staticInstanceIds(
                    resolved.name(), resolved.minInstances(), resolved.staticInstanceNames());
            Set<String> activeIds = clusterState.getInstancesByGroup(groupName).stream()
                    .filter(i -> i.state() != InstanceState.STOPPED && i.state() != InstanceState.CRASHED)
                    .map(InstanceInfo::id)
                    .collect(Collectors.toSet());

            String missingId = expectedIds.stream()
                    .filter(id -> !activeIds.contains(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "All static instances for group " + groupName + " are already running"));
            placementCoordinator.placeResolvedInstance(
                    resolved, missingId, variableOverrides, this::ensureLeaseCurrent, this::clearStartRetryBudget);
        } else {
            int current = clusterState.getInstancesByGroup(groupName).size();
            if (current >= group.maxInstances()) {
                throw new IllegalStateException(
                        "Group " + groupName + " already at max instances (" + group.maxInstances() + ")");
            }
            placeInstance(group, variableOverrides);
        }
    }

    public boolean scheduleReplacement(String groupName, String instanceId) {
        var group = groupManager.get(groupName).orElse(null);
        if (group == null) {
            logger.warn("Cannot schedule replacement for {}: group {} not found", instanceId, groupName);
            return false;
        }

        GroupConfig resolved = groupManager.resolveGroup(groupName);
        if (globalMaintenanceCheck.getAsBoolean() || resolved.maintenance()) {
            logger.debug("Skipping replacement for {} (maintenance active for group {})", instanceId, groupName);
            return false;
        }
        if ("MANUAL".equals(resolved.scalingMode())) {
            logger.debug("Skipping replacement for {} (group {} is manual)", instanceId, groupName);
            return false;
        }
        if (crashLoopDetector.isCrashLoopPaused(groupName)) {
            logger.debug("Skipping replacement for {} (crash loop paused for group {})", instanceId, groupName);
            return false;
        }

        var existing = clusterState.getInstance(instanceId).orElse(null);
        if (existing != null
                && existing.state() != InstanceState.STOPPED
                && existing.state() != InstanceState.CRASHED) {
            logger.debug(
                    "Skipping replacement for {}: instance already active in state {}", instanceId, existing.state());
            return true;
        }

        if (!resolved.isStatic() && !"STATIC".equals(resolved.scalingMode())) {
            long activeCount = clusterState.getInstancesByGroup(groupName).stream()
                    .filter(i -> i.state() != InstanceState.STOPPED && i.state() != InstanceState.CRASHED)
                    .count();
            if (activeCount >= resolved.maxInstances()) {
                logger.debug(
                        "Skipping replacement for {}: group {} already at max active instances", instanceId, groupName);
                return false;
            }
        }

        return placementCoordinator.placeResolvedInstance(
                resolved, instanceId, this::ensureLeaseCurrent, this::clearStartRetryBudget);
    }

    /**
     * Stop a specific instance.
     */
    public boolean stopInstance(String instanceId, boolean force) {
        Optional<InstanceInfo> instanceOpt = clusterState.getInstance(instanceId);
        if (instanceOpt.isEmpty()) {
            logger.warn("Cannot stop instance {}: not found", instanceId);
            return false;
        }

        InstanceInfo instance = instanceOpt.get();
        var stopMsg = ControllerMessage.newBuilder()
                .setStopInstance(
                        StopInstance.newBuilder().setInstanceId(instanceId).setForce(force))
                .build();

        if (!nodeMessageDispatcher.dispatch(instance.nodeId(), stopMsg)) {
            logger.warn("Node {} has no active session, cannot stop {}", instance.nodeId(), instanceId);
            return false;
        }
        clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
        logger.debug("Stop command sent for instance {} (force={})", instanceId, force);
        return true;
    }

    /**
     * Stop an orphan instance the controller has no record of, addressed directly by the
     * node reporting it. Unlike {@link #stopInstance}, this does not require a
     * {@link ClusterState} entry -- it exists for reconcile-on-reconnect, where a daemon
     * reports running an instance whose {@code StopInstance} was lost (e.g. across a
     * scale-down + reconnect) so it keeps holding its port and resources.
     */
    public boolean stopOrphanInstanceOnNode(String nodeId, String instanceId) {
        var stopMsg = ControllerMessage.newBuilder()
                .setStopInstance(
                        StopInstance.newBuilder().setInstanceId(instanceId).setForce(true))
                .build();
        if (!nodeMessageDispatcher.dispatch(nodeId, stopMsg)) {
            logger.warn("Cannot stop orphan instance {}: node {} has no active session", instanceId, nodeId);
            return false;
        }
        logger.warn("Sent StopInstance for orphan instance {} on node {} (no controller record)", instanceId, nodeId);
        return true;
    }

    public void rollingRestart(DeploymentRecord deployment) {
        String key = deploymentKey(deployment);
        if (!activeDeployments.add(key)) {
            logger.debug(
                    "Skipping duplicate rolling restart for group {} revision {}",
                    deployment.groupName(),
                    deployment.revision());
            return;
        }
        try {
            deploymentReconciler.rollingRestart(
                    deployment, action -> ensureLeaseCurrent(deployment.groupName(), action));
        } finally {
            activeDeployments.remove(key);
        }
    }

    /**
     * Send a command to a running instance's stdin.
     */
    public void sendCommand(String instanceId, String command) {
        Optional<InstanceInfo> instanceOpt = clusterState.getInstance(instanceId);
        if (instanceOpt.isEmpty()) {
            logger.warn("Cannot send command to {}: not found", instanceId);
            return;
        }

        InstanceInfo instance = instanceOpt.get();
        var cmdMsg = ControllerMessage.newBuilder()
                .setSendCommand(
                        SendCommand.newBuilder().setInstanceId(instanceId).setCommand(command))
                .build();

        if (!nodeMessageDispatcher.dispatch(instance.nodeId(), cmdMsg)) {
            logger.warn("Node {} has no active session, cannot send command to {}", instance.nodeId(), instanceId);
        }
    }

    private void reconcilePersistedDeployment(DeploymentRecord deployment) {
        String key = deploymentKey(deployment);
        if (!activeDeployments.add(key)) {
            return;
        }
        Thread.ofVirtual()
                .name("reconcile-deploy-" + deployment.groupName() + "-r" + deployment.revision())
                .start(() -> {
                    try {
                        deploymentReconciler.rollingRestart(
                                deployment, action -> ensureLeaseCurrent(deployment.groupName(), action));
                    } finally {
                        activeDeployments.remove(key);
                    }
                });
    }

    // Single-writer authority: the per-group Redis lease collapsed onto leadership. Both gate methods
    // ask "am I the leader?". The group name is kept for log diagnostics. The scheduler tick is already
    // leader-gated; ensureLeaseCurrent is the belt-and-suspenders mid-work re-check that catches a
    // leadership change between the top-of-tick check and the actual cluster mutation.
    @Override
    public boolean ownsGroupLease(String groupName) {
        return leadership.isLeader();
    }

    @Override
    public boolean ensureLeaseCurrent(String groupName, String action) {
        if (leadership.isLeader()) {
            return true;
        }
        logger.warn("Aborting {} for group {} because this controller is no longer the leader", action, groupName);
        return false;
    }

    private static String deploymentKey(DeploymentRecord deployment) {
        return deployment.groupName() + ":" + deployment.revision();
    }
}
