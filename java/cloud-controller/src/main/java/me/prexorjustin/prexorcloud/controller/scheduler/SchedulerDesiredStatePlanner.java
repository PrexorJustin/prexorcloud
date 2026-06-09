package me.prexorjustin.prexorcloud.controller.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import me.prexorjustin.prexorcloud.common.identity.InstanceIdGenerator;
import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.event_choreography.EventChoreographer;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

/**
 * Computes scheduler desired state without performing side effects.
 */
public final class SchedulerDesiredStatePlanner {

    public record GroupPlan(
            GroupConfig requestedGroup,
            GroupConfig resolvedGroup,
            List<String> staticInstanceIdsToPlace,
            int dynamicPlacementsToAdd,
            String scaleDownInstanceId,
            String skipReason) {

        public boolean skipped() {
            return skipReason != null;
        }
    }

    private final GroupManager groupManager;
    private final ClusterState clusterState;
    private final ScalingEvaluator scalingEvaluator;
    private final CrashLoopDetector crashLoopDetector;
    private final java.util.function.BooleanSupplier globalMaintenanceCheck;
    private final EventChoreographer eventChoreographer; // nullable
    private final java.util.function.Supplier<java.time.Instant> clock;

    public SchedulerDesiredStatePlanner(
            GroupManager groupManager,
            ClusterState clusterState,
            ScalingEvaluator scalingEvaluator,
            CrashLoopDetector crashLoopDetector,
            java.util.function.BooleanSupplier globalMaintenanceCheck) {
        this(
                groupManager,
                clusterState,
                scalingEvaluator,
                crashLoopDetector,
                globalMaintenanceCheck,
                null,
                java.time.Instant::now);
    }

    public SchedulerDesiredStatePlanner(
            GroupManager groupManager,
            ClusterState clusterState,
            ScalingEvaluator scalingEvaluator,
            CrashLoopDetector crashLoopDetector,
            java.util.function.BooleanSupplier globalMaintenanceCheck,
            EventChoreographer eventChoreographer,
            java.util.function.Supplier<java.time.Instant> clock) {
        this.groupManager = groupManager;
        this.clusterState = clusterState;
        this.scalingEvaluator = scalingEvaluator;
        this.crashLoopDetector = crashLoopDetector;
        this.globalMaintenanceCheck = globalMaintenanceCheck;
        this.eventChoreographer = eventChoreographer;
        this.clock = clock != null ? clock : java.time.Instant::now;
    }

    /**
     * Topological sort of groups by their dependsOn relationships. Returns tiers
     * sorted by {@code priority} descending, then {@code startupWeight}
     * descending — so higher-priority groups place first within a tier under
     * scarce resources.
     */
    private static final Comparator<GroupConfig> WITHIN_TIER_ORDER = Comparator.comparingInt(GroupConfig::priority)
            .thenComparingInt(GroupConfig::startupWeight)
            .reversed();

    public List<List<GroupConfig>> planEvaluationOrder(java.util.Collection<GroupConfig> groups) {
        Map<String, GroupConfig> byName = new LinkedHashMap<>();
        Map<String, Set<String>> deps = new HashMap<>();
        for (GroupConfig group : groups) {
            byName.put(group.name(), group);
            deps.put(group.name(), new HashSet<>(group.dependsOn()));
        }

        List<List<GroupConfig>> tiers = new ArrayList<>();
        Set<String> placed = new HashSet<>();

        while (placed.size() < byName.size()) {
            List<GroupConfig> tier = new ArrayList<>();
            for (var entry : byName.entrySet()) {
                String name = entry.getKey();
                if (placed.contains(name)) {
                    continue;
                }
                boolean ready = deps.get(name).stream()
                        .allMatch(dependency -> placed.contains(dependency) || !byName.containsKey(dependency));
                if (ready) {
                    tier.add(entry.getValue());
                }
            }
            if (tier.isEmpty()) {
                tier = byName.entrySet().stream()
                        .filter(entry -> !placed.contains(entry.getKey()))
                        .map(Map.Entry::getValue)
                        .sorted(WITHIN_TIER_ORDER)
                        .toList();
                tiers.add(tier);
                break;
            }
            tier.sort(WITHIN_TIER_ORDER);
            tier.forEach(group -> placed.add(group.name()));
            tiers.add(List.copyOf(tier));
        }

        return List.copyOf(tiers);
    }

    public GroupPlan planGroup(GroupConfig group) {
        java.time.Instant now = clock.get();
        GroupConfig choreographed = applyChoreography(group, now);
        if (globalMaintenanceCheck.getAsBoolean()) {
            return skipped(group, "global maintenance");
        }
        if (choreographed.maintenance()) {
            return skipped(group, "maintenance mode");
        }
        if ("MANUAL".equals(choreographed.scalingMode())) {
            return skipped(group, "manual scaling");
        }
        if (crashLoopDetector.isCrashLoopPaused(group.name())) {
            return skipped(group, "crash loop paused");
        }

        for (String dependency : choreographed.dependsOn()) {
            boolean dependencyAvailable = clusterState.getInstancesByGroup(dependency).stream()
                    .anyMatch(instance -> instance.state() == InstanceState.RUNNING);
            if (!dependencyAvailable) {
                return skipped(group, "dependency " + dependency + " has no running instances");
            }
        }

        GroupConfig resolved = applyChoreography(groupManager.resolveGroup(group.name()), now);
        if (resolved.isStatic() || "STATIC".equals(resolved.scalingMode())) {
            List<String> expectedIds = InstanceIdGenerator.staticInstanceIds(
                    resolved.name(), resolved.minInstances(), resolved.staticInstanceNames());
            Set<String> activeIds = clusterState.getInstancesByGroup(resolved.name()).stream()
                    .filter(instance ->
                            instance.state() != InstanceState.STOPPED && instance.state() != InstanceState.CRASHED)
                    .map(InstanceInfo::id)
                    .collect(Collectors.toSet());
            List<String> missingIds =
                    expectedIds.stream().filter(id -> !activeIds.contains(id)).toList();
            return new GroupPlan(group, resolved, missingIds, 0, null, null);
        }

        return new GroupPlan(
                group,
                resolved,
                List.of(),
                scalingEvaluator.evaluateScaleUp(choreographed),
                scalingEvaluator.evaluateScaleDown(choreographed),
                null);
    }

    private GroupConfig applyChoreography(GroupConfig source, java.time.Instant now) {
        if (eventChoreographer == null) return source;
        return eventChoreographer.apply(source, now);
    }

    private static GroupPlan skipped(GroupConfig group, String reason) {
        return new GroupPlan(group, group, List.of(), 0, null, reason);
    }
}
