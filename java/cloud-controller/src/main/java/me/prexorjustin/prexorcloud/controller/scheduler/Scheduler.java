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
import me.prexorjustin.prexorcloud.controller.cluster.ClusterLeaseManager;
import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentReconciler;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord;
import me.prexorjustin.prexorcloud.controller.event_choreography.EventChoreographer;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
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
    private final DistributedLeaseManager leaseManager; // nullable — null when Redis unavailable
    private final StartRetryWakeupQueue startRetryWakeupQueue; // nullable — null when Redis unavailable
    private final EventChoreographer eventChoreographer; // nullable
    private final MetricsCollector metricsCollector; // nullable
    // Tracer for the scheduler.tick span (Track D.2). Defaults to no-op; bootstrap swaps in the
    // real tracer when telemetry is enabled, so tests and telemetry-off deployments cost nothing.
    private io.opentelemetry.api.trace.Tracer tracer =
            io.opentelemetry.api.OpenTelemetry.noop().getTracer("prexorcloud-controller");
    private final StartRetryOrchestrator startRetry;
    private final RecoveryOrchestrator recovery;
    private final Set<String> activeDeployments = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Cluster-singleton gate for reconcilePersistedDeployments. Null when no
    // Raft control plane is available (tests, single-controller dev installs
    // running without it) — body falls through to its pre-Phase-8 behaviour.
    private volatile ClusterLeaseManager clusterLeaseManager;
    private static final java.time.Duration DEPLOYMENT_RECONCILER_LEASE_TTL = java.time.Duration.ofMinutes(5);

    private ScheduledExecutorService executor;

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
            DistributedLeaseManager leaseManager,
            StartRetryWakeupQueue startRetryWakeupQueue,
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
                leaseManager,
                startRetryWakeupQueue,
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
            DistributedLeaseManager leaseManager,
            StartRetryWakeupQueue startRetryWakeupQueue,
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
                leaseManager,
                startRetryWakeupQueue,
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
            DistributedLeaseManager leaseManager,
            StartRetryWakeupQueue startRetryWakeupQueue,
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
        this.leaseManager = leaseManager;
        this.startRetryWakeupQueue = startRetryWakeupQueue;
        this.eventChoreographer = eventChoreographer;
        this.metricsCollector = metricsCollector;
        this.startRetry = new StartRetryOrchestrator(
                workflowStateStore,
                clusterState,
                stateStore,
                groupManager,
                placementCoordinator,
                this,
                leaseManager,
                startRetryWakeupQueue,
                () -> this.executor);
        this.recovery = new RecoveryOrchestrator(
                clusterState,
                workflowStateStore,
                stateStore,
                groupManager,
                placementCoordinator,
                this,
                leaseManager,
                evaluationIntervalSeconds);
        if (leaseManager != null) {
            leaseManager.addLeaseChangeListener(this::onLeaseAcquired);
        }
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(
                this::evaluate, evaluationIntervalSeconds, evaluationIntervalSeconds, TimeUnit.SECONDS);
        if (startRetryWakeupQueue != null) {
            executor.scheduleAtFixedRate(startRetry::processDueWakeupsSafely, 1, 1, TimeUnit.SECONDS);
        }
        logger.debug("Scheduler started (interval={}s)", evaluationIntervalSeconds);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (leaseManager != null) {
            leaseManager.releaseAll();
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
                startRetry.processDueWakeupsSafely();
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
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Scheduler evaluation failed: {}", e.getMessage(), e);
            span.recordException(e);
        } finally {
            spanScope.close();
            span.setAttribute("scheduler.groups_evaluated", (long) groupsEvaluated);
            span.setStatus(
                    success
                            ? io.opentelemetry.api.trace.StatusCode.OK
                            : io.opentelemetry.api.trace.StatusCode.ERROR);
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

        DistributedLeaseManager.Lease lease = acquireGroupLease(group.name());
        if (leaseManager != null && lease == null) {
            logger.trace("Skipping group {} (leased by another controller)", group.name());
            return;
        }

        executeGroupPlan(plan, lease);
    }

    private void executeGroupPlan(SchedulerDesiredStatePlanner.GroupPlan plan, DistributedLeaseManager.Lease lease) {
        GroupConfig resolved = plan.resolvedGroup();
        if (!plan.staticInstanceIdsToPlace().isEmpty()) {
            for (String instanceId : plan.staticInstanceIdsToPlace()) {
                if (!placementCoordinator.placeResolvedInstance(
                        resolved, instanceId, lease, this::ensureLeaseCurrent, this::clearStartRetryBudget)) {
                    logger.warn("No eligible node for static instance {}", instanceId);
                    break;
                }
            }
            return;
        }

        for (int i = 0; i < plan.dynamicPlacementsToAdd(); i++) {
            if (!placeResolvedInstance(resolved, lease)) {
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
    }

    /**
     * Place a new dynamic instance for the given group, using gap-filling ID
     * generation.
     */
    public boolean placeInstance(GroupConfig group) {
        return placeInstance(group, acquireGroupLease(group.name()));
    }

    private boolean placeInstance(GroupConfig group, DistributedLeaseManager.Lease lease) {
        if (leaseManager != null && lease == null) {
            logger.debug("Skipping placement for group {} because another controller owns the lease", group.name());
            return false;
        }
        return placeResolvedInstance(groupManager.resolveGroup(group.name()), lease);
    }

    private boolean placeResolvedInstance(GroupConfig resolved, DistributedLeaseManager.Lease lease) {
        Set<String> existingIds = new HashSet<>(
                clusterState.getAllInstances().stream().map(InstanceInfo::id).toList());
        String instanceId = InstanceIdGenerator.generateDynamic(resolved.name(), existingIds);

        return placementCoordinator.placeResolvedInstance(
                resolved, instanceId, lease, this::ensureLeaseCurrent, this::clearStartRetryBudget);
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
        ClusterLeaseManager mgr = clusterLeaseManager;
        if (mgr == null) {
            reconcilePersistedDeploymentsBody();
            return;
        }
        // Cluster-singleton: pre-Phase-8 every controller iterated this list in
        // parallel and raced on Mongo. Skipping when another controller holds
        // the lease is the desired behaviour — next tick retries.
        mgr.runUnderLease("deployment-reconciler", DEPLOYMENT_RECONCILER_LEASE_TTL, this::reconcilePersistedDeploymentsBody);
    }

    private void reconcilePersistedDeploymentsBody() {
        for (var deployment : stateStore.getDeploymentsByState("IN_PROGRESS", 50)) {
            reconcilePersistedDeployment(deployment);
        }
    }

    /**
     * Opt the scheduler into the Raft-backed deployment-reconciler lease. Bootstrap
     * wires this after the cluster control plane is up; tests skip it and run the
     * reconciler unguarded.
     */
    public void setClusterLeaseManager(ClusterLeaseManager clusterLeaseManager) {
        this.clusterLeaseManager = clusterLeaseManager;
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

    private void reconcilePersistedStartRetriesForGroup(String groupName) {
        startRetry.reconcilePersistedForGroup(groupName);
    }

    private void reconcilePersistedDeploymentsForGroup(String groupName) {
        stateStore.getInProgressDeployment(groupName).ifPresent(this::reconcilePersistedDeployment);
    }

    /**
     * Manually schedule one new instance for a group (via REST API). For static
     * groups, places the next missing static ID. For dynamic groups, uses
     * gap-filling ID generation.
     */
    public void scheduleOne(String groupName) {
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
            var lease = requireGroupLease(groupName, "manual scheduling");
            placementCoordinator.placeResolvedInstance(
                    resolved, missingId, lease, this::ensureLeaseCurrent, this::clearStartRetryBudget);
        } else {
            int current = clusterState.getInstancesByGroup(groupName).size();
            if (current >= group.maxInstances()) {
                throw new IllegalStateException(
                        "Group " + groupName + " already at max instances (" + group.maxInstances() + ")");
            }
            placeInstance(group, requireGroupLease(groupName, "manual scheduling"));
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

        DistributedLeaseManager.Lease lease = acquireGroupLease(groupName);
        if (leaseManager != null && lease == null) {
            logger.debug(
                    "Skipping replacement for {} in group {} because another controller owns the lease",
                    instanceId,
                    groupName);
            return false;
        }
        return placementCoordinator.placeResolvedInstance(
                resolved, instanceId, lease, this::ensureLeaseCurrent, this::clearStartRetryBudget);
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
            DistributedLeaseManager.Lease lease = acquireGroupLease(deployment.groupName());
            if (leaseManager != null && lease == null) {
                logger.debug(
                        "Skipping rolling restart for group {} revision {} because another controller owns the lease",
                        deployment.groupName(),
                        deployment.revision());
                return;
            }
            deploymentReconciler.rollingRestart(
                    deployment, action -> ensureLeaseCurrent(lease, deployment.groupName(), action));
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
                        DistributedLeaseManager.Lease lease = acquireGroupLease(deployment.groupName());
                        if (leaseManager != null && lease == null) {
                            return;
                        }
                        deploymentReconciler.rollingRestart(
                                deployment, action -> ensureLeaseCurrent(lease, deployment.groupName(), action));
                    } finally {
                        activeDeployments.remove(key);
                    }
                });
    }

    @Override
    public boolean ownsGroupLease(String groupName) {
        return leaseManager == null
                || leaseManager.tryAcquireLease("group:" + groupName).isPresent();
    }

    @Override
    public DistributedLeaseManager.Lease acquireGroupLease(String groupName) {
        if (leaseManager == null) {
            return null;
        }
        return leaseManager.tryAcquireLease("group:" + groupName).orElse(null);
    }

    private DistributedLeaseManager.Lease requireGroupLease(String groupName, String action) {
        DistributedLeaseManager.Lease lease = acquireGroupLease(groupName);
        if (leaseManager != null && lease == null) {
            throw new IllegalStateException(
                    "cannot " + action + " for group '" + groupName + "' because another controller owns its lease");
        }
        return lease;
    }

    @Override
    public boolean ensureLeaseCurrent(DistributedLeaseManager.Lease lease, String groupName, String action) {
        if (leaseManager == null || (lease != null && leaseManager.isCurrent(lease))) {
            return true;
        }
        logger.warn("Aborting {} for group {} because the lease fencing token is no longer current", action, groupName);
        return false;
    }

    private void onLeaseAcquired(DistributedLeaseManager.Lease lease) {
        String groupName = groupNameFromLease(lease.resource());
        if (groupName == null) return;
        logger.debug("Reconciling persisted workflows after acquiring lease {}", lease.resource());
        recovery.reconcileForGroup(groupName);
        reconcilePersistedStartRetriesForGroup(groupName);
        reconcilePersistedDeploymentsForGroup(groupName);
    }

    private static String deploymentKey(DeploymentRecord deployment) {
        return deployment.groupName() + ":" + deployment.revision();
    }

    private static String groupNameFromLease(String resource) {
        String prefix = "group:";
        if (resource == null || !resource.startsWith(prefix) || resource.length() == prefix.length()) {
            return null;
        }
        return resource.substring(prefix.length());
    }
}
