package me.prexorjustin.prexorcloud.controller.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchedulerDesiredStatePlannerTest {

    private ClusterState clusterState;
    private GroupManager groupManager;
    private ScalingEvaluator scalingEvaluator;
    private CrashLoopDetector crashLoopDetector;
    private SchedulerDesiredStatePlanner planner;

    @BeforeEach
    void setUp() {
        clusterState = new ClusterState(new EventBus());
        groupManager = mock(GroupManager.class);
        scalingEvaluator = mock(ScalingEvaluator.class);
        crashLoopDetector = mock(CrashLoopDetector.class);
        planner = new SchedulerDesiredStatePlanner(
                groupManager, clusterState, scalingEvaluator, crashLoopDetector, () -> false);
    }

    @Test
    void planEvaluationOrderRespectsDependencies() {
        GroupConfig lobby = stubGroup("lobby", "DYNAMIC", List.of(), List.of());
        GroupConfig game = stubGroup("game", "DYNAMIC", List.of("lobby"), List.of());
        GroupConfig proxy = stubGroup("proxy", "DYNAMIC", List.of("game"), List.of());

        var tiers = planner.planEvaluationOrder(List.of(proxy, game, lobby));

        assertEquals(
                List.of("lobby"), tiers.get(0).stream().map(GroupConfig::name).toList());
        assertEquals(
                List.of("game"), tiers.get(1).stream().map(GroupConfig::name).toList());
        assertEquals(
                List.of("proxy"), tiers.get(2).stream().map(GroupConfig::name).toList());
    }

    @Test
    void planEvaluationOrderSortsTierByPriorityThenStartupWeight() {
        GroupConfig low = stubGroupWithPriorityAndWeight("low", 0, 100);
        GroupConfig high = stubGroupWithPriorityAndWeight("high", 10, 0);
        GroupConfig medium = stubGroupWithPriorityAndWeight("medium", 5, 50);

        var tiers = planner.planEvaluationOrder(List.of(low, medium, high));

        assertEquals(1, tiers.size());
        assertEquals(
                List.of("high", "medium", "low"),
                tiers.get(0).stream().map(GroupConfig::name).toList());
    }

    @Test
    void planEvaluationOrderUsesStartupWeightAsTiebreakerAtEqualPriority() {
        GroupConfig a = stubGroupWithPriorityAndWeight("a", 5, 10);
        GroupConfig b = stubGroupWithPriorityAndWeight("b", 5, 100);

        var tiers = planner.planEvaluationOrder(List.of(a, b));

        assertEquals(
                List.of("b", "a"), tiers.get(0).stream().map(GroupConfig::name).toList());
    }

    @Test
    void planGroupSkipsWhenDependencyHasNoRunningInstances() {
        GroupConfig group = stubGroup("game", "DYNAMIC", List.of("lobby"), List.of());

        var plan = planner.planGroup(group);

        assertTrue(plan.skipped());
        assertTrue(plan.skipReason().contains("dependency lobby"));
        verify(scalingEvaluator, never()).evaluateScaleUp(group);
        verify(groupManager, never()).resolveGroup("game");
    }

    @Test
    void planGroupReturnsMissingStaticInstanceIds() {
        GroupConfig group = stubGroup("lobby", "STATIC", List.of(), List.of("lobby-1", "lobby-2"));
        when(groupManager.resolveGroup("lobby")).thenReturn(group);
        when(crashLoopDetector.isCrashLoopPaused("lobby")).thenReturn(false);
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));

        var plan = planner.planGroup(group);

        assertFalse(plan.skipped());
        assertEquals(List.of("lobby-2"), plan.staticInstanceIdsToPlace());
        assertEquals(0, plan.dynamicPlacementsToAdd());
    }

    @Test
    void planGroupReturnsDynamicScaleActions() {
        GroupConfig group = stubGroup("lobby", "DYNAMIC", List.of(), List.of());
        when(groupManager.resolveGroup("lobby")).thenReturn(group);
        when(groupManager.get("lobby")).thenReturn(Optional.of(group));
        when(crashLoopDetector.isCrashLoopPaused("lobby")).thenReturn(false);
        when(scalingEvaluator.evaluateScaleUp(group)).thenReturn(2);
        when(scalingEvaluator.evaluateScaleDown(group)).thenReturn("lobby-4");

        var plan = planner.planGroup(group);

        assertFalse(plan.skipped());
        assertEquals(2, plan.dynamicPlacementsToAdd());
        assertEquals("lobby-4", plan.scaleDownInstanceId());
        assertTrue(plan.staticInstanceIdsToPlace().isEmpty());
    }

    private static GroupConfig stubGroup(
            String name, String scalingMode, List<String> dependsOn, List<String> staticNames) {
        return stubGroup(name, scalingMode, dependsOn, staticNames, 0, 0);
    }

    private static GroupConfig stubGroupWithPriorityAndWeight(String name, int priority, int startupWeight) {
        return stubGroup(name, "DYNAMIC", List.of(), List.of(), priority, startupWeight);
    }

    private static GroupConfig stubGroup(
            String name,
            String scalingMode,
            List<String> dependsOn,
            List<String> staticNames,
            int priority,
            int startupWeight) {
        return new GroupConfig(
                name,
                null,
                "PAPER",
                "",
                "server.jar",
                List.of(),
                scalingMode,
                2,
                10,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                true,
                0,
                "STATIC".equals(scalingMode),
                staticNames,
                List.of(),
                "",
                false,
                dependsOn,
                startupWeight,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                priority,
                1024,
                List.of(),
                Map.of(),
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }
}
