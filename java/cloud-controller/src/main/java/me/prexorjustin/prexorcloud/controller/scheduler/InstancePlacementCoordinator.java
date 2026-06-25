package me.prexorjustin.prexorcloud.controller.scheduler;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableResolver;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableValidator;
import me.prexorjustin.prexorcloud.controller.grpc.DaemonServiceImpl;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.InstanceCompositionPlan;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.InstanceCompositionPlanner;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.NodeState;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.CompositionPlan;
import me.prexorjustin.prexorcloud.protocol.ConfigPatch;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.ExtensionArtifact;
import me.prexorjustin.prexorcloud.protocol.InstanceState;
import me.prexorjustin.prexorcloud.protocol.RuntimeArtifact;
import me.prexorjustin.prexorcloud.protocol.RuntimeIsolation;
import me.prexorjustin.prexorcloud.protocol.StartInstance;
import me.prexorjustin.prexorcloud.protocol.TemplateRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns instance placement side effects: node selection, reservation, composition planning, and start dispatch.
 */
public final class InstancePlacementCoordinator {

    @FunctionalInterface
    public interface LeaseGuard {
        boolean ensureLeaseCurrent(String groupName, String action);
    }

    private static final Logger logger = LoggerFactory.getLogger(InstancePlacementCoordinator.class);

    private final ClusterState clusterState;
    private final NodeSelector nodeSelector;
    private final ScalingEvaluator scalingEvaluator;
    private final StateStore stateStore;
    private final InstanceCompositionPlanner compositionPlanner;
    private final NodeMessageDispatcher nodeMessageDispatcher;
    private final String controllerHttpUrl;
    // Single-writer authority: the leader is the sole controller that issues tokens + dispatches.
    // Defaults to always-leader so tests behave unchanged; bootstrap injects the real elector.
    private volatile Leadership leadership = Leadership.alwaysLeader();
    // Tracer for placement spans (Track D.2); no-op default, swapped in by bootstrap when on.
    private io.opentelemetry.api.trace.Tracer tracer =
            io.opentelemetry.api.OpenTelemetry.noop().getTracer("prexorcloud-controller");

    /**
     * Test-only checkpoint between composition-plan persistence and dispatch. Fires once
     * (the field self-clears) so the standby-promotion harness can deterministically
     * fail the controller over after the plan is durable but before any daemon RPC is
     * issued. Production code never sets this.
     */
    public volatile Runnable preDispatchHookForTesting;

    public InstancePlacementCoordinator(
            ClusterState clusterState,
            NodeSelector nodeSelector,
            ScalingEvaluator scalingEvaluator,
            StateStore stateStore,
            InstanceCompositionPlanner compositionPlanner,
            NodeMessageDispatcher nodeMessageDispatcher,
            String controllerHttpUrl) {
        this.clusterState = clusterState;
        this.nodeSelector = nodeSelector;
        this.scalingEvaluator = scalingEvaluator;
        this.stateStore = stateStore;
        this.compositionPlanner = compositionPlanner;
        this.nodeMessageDispatcher = nodeMessageDispatcher;
        this.controllerHttpUrl = controllerHttpUrl;
    }

    /** Swap in the real OpenTelemetry tracer (Track D.2). Null restores the no-op default. */
    public void setTracer(io.opentelemetry.api.trace.Tracer tracer) {
        this.tracer = tracer != null
                ? tracer
                : io.opentelemetry.api.OpenTelemetry.noop().getTracer("prexorcloud-controller");
    }

    public boolean placeResolvedInstance(
            GroupConfig resolved, String instanceId, LeaseGuard leaseGuard, Consumer<String> clearStartRetryBudget) {
        return me.prexorjustin.prexorcloud.controller.observability.telemetry.Spans.call(
                tracer,
                "placement.evaluate",
                () -> doPlaceResolvedInstance(resolved, instanceId, leaseGuard, clearStartRetryBudget));
    }

    private boolean doPlaceResolvedInstance(
            GroupConfig resolved, String instanceId, LeaseGuard leaseGuard, Consumer<String> clearStartRetryBudget) {
        if (!leaseGuard.ensureLeaseCurrent(resolved.name(), "schedule instance " + instanceId)) {
            return false;
        }

        List<NodeState> nodes = new ArrayList<>(clusterState.getAllNodes());
        var request = new InstanceRequest(
                resolved.name(),
                resolved.memoryMb(),
                resolved.cpuReservation(),
                resolved.diskReservationMb(),
                resolved.portRangeStart(),
                resolved.portRangeEnd(),
                resolved.nodeAffinity(),
                resolved.nodeAntiAffinity(),
                resolved.spreadConstraint(),
                computeBucketCounts(resolved.name(), resolved.spreadConstraint(), nodes));

        var selected = nodeSelector.select(request, nodes);
        if (selected.isEmpty()) {
            logger.warn("No eligible node available for group {}", resolved.name());
            return false;
        }

        NodeState node = selected.get();
        var projection = ResourceAccounting.project(node, request);
        if (projection.memoryHighWatermark()) {
            logger.warn(
                    "Scheduling {} on {} will raise memory reservation to {}/{}MB ({})",
                    instanceId,
                    node.nodeId(),
                    projection.projectedUsedMemoryMb(),
                    projection.totalMemoryMb(),
                    ResourceAccounting.MEMORY_HIGH_WATERMARK);
        }
        if (projection.cpuHighWatermark()) {
            logger.warn(
                    "Scheduling {} on {} will keep CPU usage near saturation ({}/{})",
                    instanceId,
                    node.nodeId(),
                    projection.projectedCpuUsage(),
                    ResourceAccounting.CPU_HARD_LIMIT);
        }
        if (projection.diskLowWatermark()) {
            logger.warn(
                    "Scheduling {} on {} will leave only {}MB free disk",
                    instanceId,
                    node.nodeId(),
                    projection.freeDiskAfterMb());
        }
        if (!leaseGuard.ensureLeaseCurrent(resolved.name(), "reserve placement for instance " + instanceId)) {
            return false;
        }

        // Atomically reserve the port + memory on the chosen node, deriving both from the node's
        // current value rather than the selection-time snapshot. Concurrent placements (the per-tier
        // scheduler fan-out forks all groups in a tier in parallel) can no longer claim the same port
        // or clobber each other's reservation. An empty result means the node filled between selection
        // and reservation, so we yield this tick and let the scheduler retry.
        var reservation = clusterState.reservePlacement(
                node.nodeId(), resolved.memoryMb(), resolved.portRangeStart(), resolved.portRangeEnd());
        if (reservation.isEmpty()) {
            logger.warn(
                    "No port available on node {} for group {} (lost reservation race)",
                    node.nodeId(),
                    resolved.name());
            return false;
        }
        int port = reservation.get().port();

        int deploymentRevision = stateStore
                .getInProgressDeployment(resolved.name())
                .map(DeploymentRecord::revision)
                .orElse(0);
        var instance = new InstanceInfo(
                instanceId,
                resolved.name(),
                node.nodeId(),
                InstanceState.SCHEDULED,
                port,
                0,
                0,
                Instant.now(),
                deploymentRevision);
        // Atomic group-cap guard: the crash-heal replacement and the min-instance reconcile can race
        // here (the per-group lease that used to serialize them is gone). Whoever loses the cap check
        // releases the port + memory it reserved above and yields, so the group never over-provisions.
        if (!clusterState.addInstanceWithinCap(instance, resolved.maxInstances())) {
            clusterState.releasePlacement(node.nodeId(), resolved.memoryMb(), port);
            clearStartRetryBudget.accept(instanceId);
            logger.info(
                    "Skipped placing {} — group {} already at max {} active instances "
                            + "(a concurrent placement won the slot)",
                    instanceId,
                    resolved.name(),
                    resolved.maxInstances());
            return false;
        }
        clearStartRetryBudget.accept(instanceId);

        final InstanceCompositionPlan compositionPlan;
        try {
            compositionPlan = compositionPlanner.plan(resolved, instanceId, node.nodeId(), port, controllerHttpUrl);
            stateStore.saveInstanceCompositionPlan(compositionPlan);
        } catch (Exception e) {
            rollbackScheduledPlacement(node.nodeId(), resolved.memoryMb(), port, instanceId, clearStartRetryBudget);
            logger.error("Failed to build composition plan for {}: {}", instanceId, e.getMessage(), e);
            return false;
        }

        Runnable hook = preDispatchHookForTesting;
        if (hook != null) {
            preDispatchHookForTesting = null;
            hook.run();
        }

        if (!leaseGuard.ensureLeaseCurrent(resolved.name(), "dispatch start for instance " + instanceId)) {
            logger.warn(
                    "Preserving scheduled placement for {} after leadership loss; recoverable start handoff will redispatch",
                    instanceId);
            return true;
        }

        logger.debug(
                "Composition plan for {} on group {}: templates={}, extensions={}, hash={}",
                instanceId,
                resolved.name(),
                compositionPlan.templates().stream()
                        .map(InstanceCompositionPlan.ResolvedTemplate::name)
                        .toList(),
                compositionPlan.extensions().stream()
                        .map(InstanceCompositionPlan.ResolvedExtension::extensionId)
                        .toList(),
                compositionPlan
                        .planHash()
                        .substring(0, Math.min(8, compositionPlan.planHash().length())));

        // ownership = leadership: only the leader issues the token and dispatches. (Delivery may
        // still relay through whichever controller holds the daemon stream until Phase 3's redirect
        // consolidates streams on the leader; authority no longer flaps on daemon reconnect.) If we
        // are not the leader, leave the SCHEDULED record + composition plan durably persisted; the
        // leader's RecoveryOrchestrator picks them up and dispatches on its next tick. This guard is
        // belt-and-suspenders — the scheduler tick is already leader-gated — covering a leadership
        // change mid-tick.
        if (!leadership.isLeader()) {
            logger.info(
                    "Placed {} on {} while not leader; the leader will issue the token and dispatch",
                    instanceId,
                    node.nodeId());
            return true;
        }

        String pluginToken = clusterState.issuePluginToken(instanceId);
        var startMessage = buildStartMessage(resolved, instance, compositionPlan, pluginToken);
        if (!dispatchStartMessage(node.nodeId(), instanceId, startMessage)) {
            logger.warn(
                    "Node {} has no active session, preserving scheduled placement for {} until recoverable redispatch",
                    node.nodeId(),
                    instanceId);
            return true;
        }

        scalingEvaluator.recordScaleAction(resolved.name());
        logger.info(
                "Instance {} scheduled on {} (port={}, memory={}MB, templates={})",
                instanceId,
                node.nodeId(),
                port,
                resolved.memoryMb(),
                compositionPlan.templates().stream()
                        .map(InstanceCompositionPlan.ResolvedTemplate::name)
                        .toList());
        return true;
    }

    /** Inject single-writer leadership (bootstrap). Tests run as always-leader. */
    public void setLeadership(Leadership leadership) {
        this.leadership = leadership;
    }

    public boolean dispatchStartMessage(String nodeId, String instanceId, ControllerMessage startMessage) {
        return me.prexorjustin.prexorcloud.controller.observability.telemetry.Spans.call(
                tracer, "placement.dispatch", () -> doDispatchStartMessage(nodeId, instanceId, startMessage));
    }

    private boolean doDispatchStartMessage(String nodeId, String instanceId, ControllerMessage startMessage) {
        if (!nodeMessageDispatcher.dispatch(nodeId, startMessage)) {
            return false;
        }
        logger.debug("Start command sent for {} to node {}", instanceId, nodeId);
        return true;
    }

    public ControllerMessage buildStartMessage(
            GroupConfig resolved, InstanceInfo instance, InstanceCompositionPlan compositionPlan, String pluginToken) {
        var startBuilder = StartInstance.newBuilder()
                .setInstanceId(instance.id())
                .setGroup(resolved.name())
                .setPort(instance.port())
                .setMemoryMb(compositionPlan.memoryMb())
                .addAllJvmArgs(compositionPlan.jvmArgs())
                .putAllEnv(compositionPlan.env())
                .setPluginToken(pluginToken)
                .setStartupTimeoutSeconds(resolved.startupTimeoutSeconds())
                .setShutdownGraceSeconds(resolved.shutdownGraceSeconds())
                .setMaxLifetimeSeconds(resolved.maxLifetimeSeconds())
                .setStaticInstance(compositionPlan.staticInstance())
                .addAllProtectedPaths(compositionPlan.protectedPaths())
                .setMaxPlayers(resolved.maxPlayers())
                .setIsolation(toWireIsolation(compositionPlan.isolation()))
                .setCompositionPlan(toWirePlan(compositionPlan))
                .putAllResolvedVariables(resolveInstanceVariables(resolved, compositionPlan));
        return ControllerMessage.newBuilder().setStartInstance(startBuilder).build();
    }

    /**
     * Resolve the typed v2 variable map threaded to the daemon for {@code %KEY%} substitution. Layers
     * the template chain's declared defaults under the group's {@code variableValues}; per-instance
     * overrides are an additive layer wired in a later increment. Validation/scope problems are logged
     * and the valid subset is applied rather than blocking placement — a typo'd variable must never
     * wedge a start. Empty when no template declares a typed variable and the group sets none.
     */
    private Map<String, String> resolveInstanceVariables(GroupConfig group, InstanceCompositionPlan plan) {
        List<VariableDef> defs = new ArrayList<>();
        for (InstanceCompositionPlan.ResolvedTemplate template : plan.templates()) {
            defs.addAll(stateStore.getTemplateVariableDefs(template.name()));
        }
        if (defs.isEmpty() && group.variableValues().isEmpty()) {
            return Map.of();
        }
        VariableValidator.Result result =
                VariableResolver.resolve(VariableResolver.mergeChain(defs), group.variableValues(), Map.of());
        if (!result.ok()) {
            logger.warn(
                    "Variable resolution for group {} produced {} issue(s); applying the valid subset: {}",
                    group.name(),
                    result.errors().size(),
                    result.errors());
        }
        return result.resolved();
    }

    private void rollbackScheduledPlacement(
            String nodeId, int memoryMb, int port, String instanceId, Consumer<String> clearStartRetryBudget) {
        clearStartRetryBudget.accept(instanceId);
        // removeInstance already releases the port (and decrements the instance count); release the
        // reserved memory delta here so the same-tick view is corrected before any heartbeat arrives.
        clusterState.removeInstance(instanceId);
        clusterState.releasePlacement(nodeId, memoryMb, port);
        stateStore.deleteInstanceCompositionPlan(instanceId);
    }

    private static CompositionPlan toWirePlan(InstanceCompositionPlan compositionPlan) {
        return CompositionPlan.newBuilder()
                .setPlanHash(compositionPlan.planHash())
                .setRuntime(RuntimeArtifact.newBuilder()
                        .setJarFile(compositionPlan.runtime().jarFile())
                        .setDownloadUrl(compositionPlan.runtime().downloadUrl())
                        .setSha256(compositionPlan.runtime().sha256())
                        .setPlatform(compositionPlan.runtime().platform())
                        .setPlatformVersion(compositionPlan.runtime().platformVersion())
                        .setCategory(DaemonServiceImpl.parseCategory(
                                compositionPlan.runtime().category()))
                        .setConfigFormat(DaemonServiceImpl.parseConfigFormat(
                                compositionPlan.runtime().configFormat())))
                .addAllTemplates(compositionPlan.templates().stream()
                        .map(template -> TemplateRef.newBuilder()
                                .setName(template.name())
                                .setHash(template.hash())
                                .build())
                        .toList())
                .addAllExtensions(compositionPlan.extensions().stream()
                        .map(extension -> ExtensionArtifact.newBuilder()
                                .setModuleId(extension.moduleId())
                                .setExtensionId(extension.extensionId())
                                .setVariantId(extension.variantId())
                                .setFileName(Path.of(extension.artifact())
                                        .getFileName()
                                        .toString())
                                .setDownloadUrl(extension.downloadUrl())
                                .setSha256(extension.sha256())
                                .setInstallPath(extension.installPath())
                                .build())
                        .toList())
                .addAllConfigPatches(compositionPlan.configPatches().stream()
                        .map(configPatch -> ConfigPatch.newBuilder()
                                .setFile(configPatch.file())
                                .setKey(configPatch.key())
                                .setValue(configPatch.value())
                                .build())
                        .toList())
                .setIsolation(toWireIsolation(compositionPlan.isolation()))
                .build();
    }

    private static RuntimeIsolation toWireIsolation(InstanceCompositionPlan.RuntimeIsolation isolation) {
        return RuntimeIsolation.newBuilder()
                .setCpuReservation(isolation.cpuReservation())
                .setDiskReservationMb(isolation.diskReservationMb())
                .build();
    }

    /**
     * Builds the {@code label-value -> existing instance count} map the selector
     * uses to spread group instances across {@code spreadConstraint} buckets.
     * The constraint may be the bare label key, or {@code key=value}; only the
     * key portion is used for bucketing.
     */
    private Map<String, Integer> computeBucketCounts(String groupName, String spreadConstraint, List<NodeState> nodes) {
        if (spreadConstraint == null || spreadConstraint.isBlank()) {
            return Map.of();
        }
        int eq = spreadConstraint.indexOf('=');
        String key = eq < 0 ? spreadConstraint : spreadConstraint.substring(0, eq);
        Map<String, String> nodeBuckets = new HashMap<>(nodes.size());
        for (NodeState node : nodes) {
            String value = node.labels().get(key);
            if (value != null) {
                nodeBuckets.put(node.nodeId(), value);
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        for (InstanceInfo instance : clusterState.getInstancesByGroup(groupName)) {
            String bucket = nodeBuckets.get(instance.nodeId());
            if (bucket != null) {
                counts.merge(bucket, 1, Integer::sum);
            }
        }
        return Map.copyOf(counts);
    }
}
