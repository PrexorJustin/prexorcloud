package me.prexorjustin.prexorcloud.controller.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ScalingEvaluator")
class ScalingEvaluatorTest {

    private ClusterState clusterState;
    private ScalingEvaluator evaluator;

    @BeforeEach
    void setUp() {
        clusterState = new ClusterState(new EventBus());
        evaluator = new ScalingEvaluator(clusterState, 30);
    }

    private GroupConfig group(String name, int min, int max, boolean isStatic, int maxPlayers) {
        String scalingMode = isStatic ? "STATIC" : "DYNAMIC";
        return new GroupConfig(
                name, // name
                null, // parent
                "PAPER", // platform
                "1.21", // platformVersion
                "server.jar", // jarFile
                List.of(), // templates
                scalingMode, // scalingMode
                min, // minInstances
                max, // maxInstances
                maxPlayers, // maxPlayers
                0.8, // scaleUpThreshold
                300, // scaleDownAfterSeconds
                0, // scaleCooldownSeconds (use default)
                false, // predictiveScaling
                0.2, // scaleUpMargin
                0, // burstCeiling
                "LOWEST_PLAYERS", // routing
                30000, // portRangeStart
                30100, // portRangeEnd
                120, // startupTimeoutSeconds
                30, // shutdownGraceSeconds
                false, // drainOnShutdown
                0, // maxLifetimeSeconds
                isStatic, // isStatic
                List.of(), // staticInstanceNames
                List.of(), // protectedPaths
                null, // fallbackGroup
                false, // defaultGroup
                List.of(), // dependsOn
                0, // startupWeight
                false, // maintenance
                "", // maintenanceMessage
                List.of(), // maintenanceBypass
                "ROLLING", // updateStrategy
                List.of(), // nodeAffinity
                List.of(), // nodeAntiAffinity
                "", // spreadConstraint
                0, // priority
                1024, // memoryMb
                List.of(), // jvmArgs
                Map.of(), // env
                List.of(), // motds
                "STATIC", // motdMode
                30, // motdIntervalSeconds
                List.of(), // attachedExtensions
                List.of(), // enabledExtensions
                List.of(), // disabledExtensions
                Map.of() // configPatches
                );
    }

    private void addInstance(String id, String groupName, InstanceState state, int playerCount, long uptimeMs) {
        clusterState.addInstance(
                new InstanceInfo(id, groupName, "node-1", state, 30000, playerCount, uptimeMs, Instant.now()));
    }

    @Nested
    @DisplayName("Scale-up evaluation")
    class ScaleUp {

        @Test
        @DisplayName("Returns needed instances when below minimum")
        void belowMinimum() {
            var cfg = group("lobby", 3, 10, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000);

            assertEquals(2, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Returns 0 when at minimum with available capacity")
        void atMinimumWithCapacity() {
            var cfg = group("lobby", 2, 10, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000);
            addInstance("lobby-2", "lobby", InstanceState.RUNNING, 20, 60000);

            assertEquals(0, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Returns 0 when at max instances")
        void atMaxInstances() {
            var cfg = group("lobby", 1, 2, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 90, 60000);
            addInstance("lobby-2", "lobby", InstanceState.RUNNING, 90, 60000);

            assertEquals(0, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Returns 1 when all running instances are above 80% capacity")
        void dynamicScaleUp() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 85, 60000);

            assertEquals(1, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Returns 0 for static groups even if above threshold")
        void staticGroupNoScaling() {
            var cfg = group("lobby", 1, 5, true, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 90, 60000);

            assertEquals(0, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Does not count STOPPED instances toward current count")
        void ignoresStoppedInstances() {
            var cfg = group("lobby", 2, 10, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000);
            addInstance("lobby-2", "lobby", InstanceState.STOPPED, 0, 0);

            assertEquals(1, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Does not count CRASHED instances toward current count")
        void ignoresCrashedInstances() {
            var cfg = group("lobby", 2, 10, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000);
            addInstance("lobby-2", "lobby", InstanceState.CRASHED, 0, 0);

            assertEquals(1, evaluator.evaluateScaleUp(cfg));
        }
    }

    @Nested
    @DisplayName("Scale-down evaluation")
    class ScaleDown {

        @Test
        @DisplayName("Returns null for static groups")
        void staticGroupNoScaleDown() {
            var cfg = group("lobby", 1, 5, true, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 0, 600000);
            addInstance("lobby-2", "lobby", InstanceState.RUNNING, 0, 600000);

            assertNull(evaluator.evaluateScaleDown(cfg));
        }

        @Test
        @DisplayName("Returns null when at minimum instances")
        void atMinimumInstances() {
            var cfg = group("lobby", 2, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 0, 600000);
            addInstance("lobby-2", "lobby", InstanceState.RUNNING, 0, 600000);

            assertNull(evaluator.evaluateScaleDown(cfg));
        }

        @Test
        @DisplayName("Returns instance ID for empty instance above timeout")
        void scaleDownEmptyInstance() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000);
            addInstance("lobby-2", "lobby", InstanceState.RUNNING, 0, 301000);

            assertEquals("lobby-2", evaluator.evaluateScaleDown(cfg));
        }

        @Test
        @DisplayName("Returns null when empty instance has not been empty long enough")
        void emptyInstanceBelowTimeout() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000);
            addInstance("lobby-2", "lobby", InstanceState.RUNNING, 0, 100000);

            assertNull(evaluator.evaluateScaleDown(cfg));
        }

        @Test
        @DisplayName("Returns null when all instances have players")
        void allInstancesHavePlayers() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 5, 600000);
            addInstance("lobby-2", "lobby", InstanceState.RUNNING, 3, 600000);

            assertNull(evaluator.evaluateScaleDown(cfg));
        }
    }

    @Nested
    @DisplayName("Cooldown")
    class Cooldown {

        @Test
        @DisplayName("Scale-up returns 0 during cooldown period")
        void cooldownBlocksScaleUp() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 90, 60000);

            evaluator.recordScaleAction("lobby");

            assertEquals(0, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Scale-up below minimum ignores cooldown")
        void belowMinimumIgnoresCooldown() {
            var cfg = group("lobby", 3, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 90, 60000);

            evaluator.recordScaleAction("lobby");

            assertEquals(2, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Scale-down returns null during cooldown period")
        void cooldownBlocksScaleDown() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 5, 600000);
            addInstance("lobby-2", "lobby", InstanceState.RUNNING, 0, 600000);

            evaluator.recordScaleAction("lobby");

            assertNull(evaluator.evaluateScaleDown(cfg));
        }
    }
}
