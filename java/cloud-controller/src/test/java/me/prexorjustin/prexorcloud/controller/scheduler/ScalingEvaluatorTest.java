package me.prexorjustin.prexorcloud.controller.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InMemoryScaleActionStore;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ScalingEvaluator")
class ScalingEvaluatorTest {

    private ClusterState clusterState;
    private InMemoryScaleActionStore scaleActionStore;
    private ScalingEvaluator evaluator;

    @BeforeEach
    void setUp() {
        clusterState = new ClusterState(new EventBus());
        scaleActionStore = new InMemoryScaleActionStore();
        evaluator = new ScalingEvaluator(clusterState, 30, scaleActionStore);
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
        addInstance(id, groupName, state, playerCount, uptimeMs, 0.0);
    }

    private void addInstance(
            String id, String groupName, InstanceState state, int playerCount, long uptimeMs, double tps1m) {
        clusterState.addInstance(new InstanceInfo(
                id, groupName, "node-1", state, 30000, playerCount, uptimeMs, Instant.now(), 0, tps1m));
    }

    private void addWarmInstance(String id, String groupName, int playerCount, long uptimeMs) {
        clusterState.addInstance(new InstanceInfo(
                        id, groupName, "node-1", InstanceState.RUNNING, 30000, playerCount, uptimeMs, Instant.now())
                .withWarm(true));
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

        @Test
        @DisplayName("Scales on aggregate load even when one instance is quiet (old all-or-nothing would not)")
        void aggregateScaleUpWithQuietInstance() {
            var cfg = group("lobby", 1, 5, false, 100);
            // Mean load = (100 + 70) / 200 = 0.85 >= 0.8 target. Instance-2 alone (0.70) is below
            // threshold, so the old "every instance saturated" rule returned 0; the aggregate rule scales.
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 100, 60000);
            addInstance("lobby-2", "lobby", InstanceState.RUNNING, 70, 60000);

            assertEquals(1, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Adds multiple instances at once when load far exceeds the target (scale-by-N)")
        void scalesByMultipleWhenLoadFarExceedsTarget() {
            var cfg = group("lobby", 1, 10, false, 100);
            // 5 instances each full (500 players). To bring mean load to the 0.8 target needs
            // ceil(500 / (0.8*100)) = 7 running, i.e. +2 in a single evaluation rather than a flat +1.
            for (int i = 1; i <= 5; i++) {
                addInstance("lobby-" + i, "lobby", InstanceState.RUNNING, 100, 60000);
            }

            assertEquals(2, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Scale-by-N never exceeds maxInstances")
        void scaleByNRespectsMaxInstances() {
            var cfg = group("lobby", 1, 6, false, 100);
            // Same saturated fleet wants +2, but only one slot remains under maxInstances=6.
            for (int i = 1; i <= 5; i++) {
                addInstance("lobby-" + i, "lobby", InstanceState.RUNNING, 100, 60000);
            }

            assertEquals(1, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Scales up when a running instance is tick-starved even if player load is low")
        void tpsDegradedTriggersScaleUp() {
            var cfg = group("lobby", 1, 5, false, 100);
            // 10/100 = 0.10 load, far below the 0.8 target -> player load alone would not scale.
            // TPS 12.0 is below the 18.0 floor, so the server is overloaded -> add one instance.
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000, 12.0);

            assertEquals(1, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Healthy TPS at low player load does not scale up")
        void healthyTpsNoScaleUp() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000, 19.5);

            assertEquals(0, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Zero TPS (no data / not a game server) is ignored, not treated as degraded")
        void zeroTpsIgnored() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000, 0.0);

            assertEquals(0, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Warm instances are not counted as serving capacity (do not dilute utilisation)")
        void warmInstancesNotServingCapacity() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 90, 60000); // serving at 0.9 load
            addWarmInstance("lobby-warm", "lobby", 0, 60000); // warm, empty
            // If the warm instance were counted, mean load = 90/200 = 0.45 < 0.8 and nothing scales.
            // Warm is excluded, so serving load stays 0.9 >= 0.8 and the group scales up.
            assertEquals(1, evaluator.evaluateScaleUp(cfg));
        }

        @Test
        @DisplayName("Warm instances do not satisfy the minimum serving floor")
        void warmInstancesDoNotCountTowardMinimum() {
            var cfg = group("lobby", 2, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000); // 1 serving
            addWarmInstance("lobby-warm", "lobby", 0, 60000); // warm does not count toward the floor
            // serving count = 1 < min 2 -> one more serving instance is needed.
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

        @Test
        @DisplayName("Cooldown survives failover: a fresh evaluator reads the last scale time from the store")
        void cooldownSurvivesFailover() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 90, 60000);

            // Old leader scaled just now and persisted it to the shared store.
            scaleActionStore.recordScaleAction("lobby", Instant.now());

            // New leader starts cold (empty in-memory map) but shares the store. Without the store
            // read it would scale up (90/100 >= 0.8); the persisted cooldown must block it.
            var freshLeader = new ScalingEvaluator(clusterState, 30, scaleActionStore);
            assertEquals(0, freshLeader.evaluateScaleUp(cfg));
        }
    }

    @Nested
    @DisplayName("Scale-up decision reasons (why (not) scaled)")
    class DecisionReasons {

        @Test
        @DisplayName("below minInstances → count + reason")
        void belowMin() {
            var cfg = group("lobby", 3, 10, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000);
            var d = evaluator.evaluateScaleUpDecision(cfg);
            assertEquals(2, d.count());
            assertTrue(d.reason().contains("below minInstances"), d.reason());
        }

        @Test
        @DisplayName("at maxInstances → 0 with reason")
        void atMax() {
            var cfg = group("lobby", 1, 2, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 90, 60000);
            addInstance("lobby-2", "lobby", InstanceState.RUNNING, 90, 60000);
            var d = evaluator.evaluateScaleUpDecision(cfg);
            assertEquals(0, d.count());
            assertTrue(d.reason().contains("at maxInstances"), d.reason());
        }

        @Test
        @DisplayName("static group → 0 with reason")
        void staticGroup() {
            var cfg = group("lobby", 1, 5, true, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 90, 60000);
            var d = evaluator.evaluateScaleUpDecision(cfg);
            assertEquals(0, d.count());
            assertTrue(d.reason().contains("static"), d.reason());
        }

        @Test
        @DisplayName("load below target → 0 with load-vs-target reason")
        void lowLoadHold() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 10, 60000);
            var d = evaluator.evaluateScaleUpDecision(cfg);
            assertEquals(0, d.count());
            assertTrue(d.reason().contains("< target"), d.reason());
        }

        @Test
        @DisplayName("high load → scale up with load-vs-target reason")
        void scaleUp() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 85, 60000);
            var d = evaluator.evaluateScaleUpDecision(cfg);
            assertEquals(1, d.count());
            assertTrue(d.reason().contains(">= target"), d.reason());
        }

        @Test
        @DisplayName("cooldown active → 0 with cooldown reason")
        void cooldown() {
            var cfg = group("lobby", 1, 5, false, 100);
            addInstance("lobby-1", "lobby", InstanceState.RUNNING, 90, 60000);
            scaleActionStore.recordScaleAction("lobby", Instant.now());
            var fresh = new ScalingEvaluator(clusterState, 30, scaleActionStore);
            var d = fresh.evaluateScaleUpDecision(cfg);
            assertEquals(0, d.count());
            assertTrue(d.reason().contains("cooldown"), d.reason());
        }
    }
}
