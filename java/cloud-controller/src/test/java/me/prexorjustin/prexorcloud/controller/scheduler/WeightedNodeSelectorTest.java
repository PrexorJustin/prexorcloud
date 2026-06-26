package me.prexorjustin.prexorcloud.controller.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.state.NodeState;
import me.prexorjustin.prexorcloud.controller.state.NodeState.NodeStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WeightedNodeSelector")
class WeightedNodeSelectorTest {

    private WeightedNodeSelector selector;
    private InstanceRequest request;

    @BeforeEach
    void setUp() {
        selector = new WeightedNodeSelector();
        request = new InstanceRequest("lobby", 512, 30000, 30010);
    }

    private NodeState node(
            String id,
            NodeStatus status,
            double cpu,
            long totalMem,
            long usedMem,
            int instanceCount,
            Set<Integer> usedPorts) {
        return new NodeState(
                id,
                "",
                status,
                cpu,
                totalMem,
                usedMem,
                10000,
                20000,
                instanceCount,
                usedPorts,
                Map.of(),
                Instant.now(),
                Instant.now(),
                null);
    }

    @Nested
    @DisplayName("Eligibility filtering")
    class EligibilityFiltering {

        @Test
        @DisplayName("Filters out DRAINING nodes")
        void filtersDraining() {
            var draining = node("n1", NodeStatus.DRAINING, 0.1, 4096, 0, 0, Set.of());
            var result = selector.select(request, List.of(draining));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Filters out UNREACHABLE nodes")
        void filtersUnreachable() {
            var unreachable = node("n1", NodeStatus.UNREACHABLE, 0.1, 4096, 0, 0, Set.of());
            var result = selector.select(request, List.of(unreachable));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Filters out nodes with insufficient memory")
        void filtersInsufficientMemory() {
            // Needs 512 MB, node has 4096-3800=296 MB free
            var lowMem = node("n1", NodeStatus.ONLINE, 0.1, 4096, 3800, 0, Set.of());
            var result = selector.select(request, List.of(lowMem));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Resource accounting marks high-watermark placements")
        void resourceAccountingHighWatermark() {
            var node = node("n1", NodeStatus.ONLINE, 0.1, 4096, 3200, 0, Set.of());
            var projection = ResourceAccounting.project(node, request);

            assertTrue(projection.fits());
            assertTrue(projection.memoryHighWatermark());
            assertFalse(projection.memoryOvercommitted());
        }

        @Test
        @DisplayName("Resource accounting rejects CPU-saturated nodes")
        void resourceAccountingRejectsCpuSaturation() {
            var saturated = node("n1", NodeStatus.ONLINE, 0.96, 4096, 1000, 0, Set.of());
            var result = selector.select(request, List.of(saturated));

            assertTrue(result.isEmpty());
            assertTrue(ResourceAccounting.project(saturated, request).cpuOvercommitted());
        }

        @Test
        @DisplayName("Resource accounting rejects explicit disk overcommit")
        void resourceAccountingRejectsDiskOvercommit() {
            var node = node("n1", NodeStatus.ONLINE, 0.1, 4096, 1000, 0, Set.of());
            var diskHeavy = new InstanceRequest("lobby", 512, 0.0, 20_000, 30000, 30010, List.of(), List.of());
            var result = selector.select(diskHeavy, List.of(node));

            assertTrue(result.isEmpty());
            assertTrue(ResourceAccounting.project(node, diskHeavy).diskOvercommitted());
        }

        @Test
        @DisplayName("Resource accounting reports CPU and disk watermarks")
        void resourceAccountingReportsCpuAndDiskWatermarks() {
            var node = node("n1", NodeStatus.ONLINE, 0.84, 4096, 1000, 0, Set.of());
            var request = new InstanceRequest("lobby", 512, 0.01, 9_000, 30000, 30010, List.of(), List.of());
            var projection = ResourceAccounting.project(node, request);

            assertTrue(projection.fits());
            assertTrue(projection.cpuHighWatermark());
            assertTrue(projection.diskLowWatermark());
        }

        @Test
        @DisplayName("Filters out nodes with no available ports")
        void filtersNoAvailablePorts() {
            // All ports in 30000-30010 are used
            Set<Integer> allPorts = Set.of(30000, 30001, 30002, 30003, 30004, 30005, 30006, 30007, 30008, 30009, 30010);
            var noPort = node("n1", NodeStatus.ONLINE, 0.1, 4096, 0, 0, allPorts);
            var result = selector.select(request, List.of(noPort));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Returns empty when no nodes available")
        void emptyNodeList() {
            var result = selector.select(request, List.of());
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scoring and selection")
    class ScoringAndSelection {

        @Test
        @DisplayName("Prefers node with more free memory")
        void prefersMoreMemory() {
            var lowMem = node("n1", NodeStatus.ONLINE, 0.5, 4096, 3000, 2, Set.of());
            var highMem = node("n2", NodeStatus.ONLINE, 0.5, 4096, 1000, 2, Set.of());

            var result = selector.select(request, List.of(lowMem, highMem));
            assertTrue(result.isPresent());
            assertEquals("n2", result.get().nodeId());
        }

        @Test
        @DisplayName("Prefers node with lower CPU usage")
        void prefersLowerCpu() {
            var highCpu = node("n1", NodeStatus.ONLINE, 0.9, 4096, 0, 0, Set.of());
            var lowCpu = node("n2", NodeStatus.ONLINE, 0.1, 4096, 0, 0, Set.of());

            var result = selector.select(request, List.of(highCpu, lowCpu));
            assertTrue(result.isPresent());
            assertEquals("n2", result.get().nodeId());
        }

        @Test
        @DisplayName("Prefers node with fewer instances for better spread")
        void prefersFewerInstances() {
            var manyInst = node("n1", NodeStatus.ONLINE, 0.5, 8192, 2000, 10, Set.of());
            var fewInst = node("n2", NodeStatus.ONLINE, 0.5, 8192, 2000, 1, Set.of());

            var result = selector.select(request, List.of(manyInst, fewInst));
            assertTrue(result.isPresent());
            assertEquals("n2", result.get().nodeId());
        }

        @Test
        @DisplayName("Selects only eligible node from mixed list")
        void selectsFromMixedList() {
            var draining = node("n1", NodeStatus.DRAINING, 0.1, 4096, 0, 0, Set.of());
            var lowMem = node("n2", NodeStatus.ONLINE, 0.1, 512, 256, 0, Set.of()); // 256 free < 512 needed
            var good = node("n3", NodeStatus.ONLINE, 0.3, 4096, 1000, 2, Set.of());

            var result = selector.select(request, List.of(draining, lowMem, good));
            assertTrue(result.isPresent());
            assertEquals("n3", result.get().nodeId());
        }

        @Test
        @DisplayName("Single eligible node is selected")
        void singleEligibleNode() {
            var single = node("n1", NodeStatus.ONLINE, 0.5, 4096, 1000, 3, Set.of());
            var result = selector.select(request, List.of(single));
            assertTrue(result.isPresent());
            assertEquals("n1", result.get().nodeId());
        }
    }

    @Nested
    @DisplayName("spreadConstraint placement")
    class SpreadConstraint {

        private NodeState labeledNode(String id, String zone) {
            return new NodeState(
                    id,
                    "",
                    NodeStatus.ONLINE,
                    0.3,
                    4096,
                    1000,
                    10000,
                    20000,
                    1,
                    Set.of(),
                    Map.of("zone", zone),
                    Instant.now(),
                    Instant.now(),
                    null);
        }

        @Test
        @DisplayName("Prefers under-loaded zone when spreadConstraint set")
        void prefersUnderLoadedBucket() {
            var heavyZone = labeledNode("n1", "a");
            var lightZone = labeledNode("n2", "b");

            var spread = new InstanceRequest(
                    "lobby", 512, 0.0, 0, 30000, 30010, List.of(), List.of(), "zone", Map.of("a", 5, "b", 0));

            var result = selector.select(spread, List.of(heavyZone, lightZone));
            assertTrue(result.isPresent());
            assertEquals("n2", result.get().nodeId());
        }

        @Test
        @DisplayName("Empty spreadConstraint falls back to base scoring")
        void noSpreadConstraintNoEffect() {
            var same1 = labeledNode("n1", "a");
            var same2 = labeledNode("n2", "b");

            var noSpread = new InstanceRequest("lobby", 512, 30000, 30010);
            var result = selector.select(noSpread, List.of(same1, same2));
            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("Nodes without the constraint label are not penalised")
        void unlabeledNodeNotPenalised() {
            var unlabeled = node("n1", NodeStatus.ONLINE, 0.3, 4096, 1000, 1, Set.of());
            var spread = new InstanceRequest(
                    "lobby", 512, 0.0, 0, 30000, 30010, List.of(), List.of(), "zone", Map.of("a", 10));

            var result = selector.select(spread, List.of(unlabeled));
            assertTrue(result.isPresent());
            assertEquals("n1", result.get().nodeId());
        }
    }

    @Nested
    @DisplayName("explainIneligibility (placement diagnostics)")
    class Explainability {

        @Test
        @DisplayName("reports a memory reason for an under-provisioned node")
        void memoryReason() {
            var lowMem = node("n1", NodeStatus.ONLINE, 0.1, 4096, 3800, 0, Set.of()); // 296 free < 512 needed
            var reasons = selector.explainIneligibility(request, List.of(lowMem));
            assertEquals(Set.of("n1"), reasons.keySet());
            assertTrue(reasons.get("n1").contains("insufficient memory"), reasons.get("n1"));
        }

        @Test
        @DisplayName("reports a port reason when the group's range is exhausted")
        void portReason() {
            Set<Integer> allPorts = Set.of(30000, 30001, 30002, 30003, 30004, 30005, 30006, 30007, 30008, 30009, 30010);
            var noPort = node("n1", NodeStatus.ONLINE, 0.1, 4096, 0, 0, allPorts);
            assertTrue(
                    selector.explainIneligibility(request, List.of(noPort)).get("n1").contains("no free port"));
        }

        @Test
        @DisplayName("reports a status reason for a non-ONLINE node")
        void statusReason() {
            var draining = node("n1", NodeStatus.DRAINING, 0.1, 4096, 0, 0, Set.of());
            var reason = selector.explainIneligibility(request, List.of(draining)).get("n1");
            assertTrue(reason.contains("not ONLINE") && reason.contains("DRAINING"), reason);
        }

        @Test
        @DisplayName("reports anti-affinity exclusion and omits eligible nodes")
        void antiAffinityReasonAndOmitsEligible() {
            var tainted = new NodeState(
                    "n1", "", NodeStatus.ONLINE, 0.2, 8192, 1000, 10000, 20000, 0, Set.of(),
                    Map.of("zone", "edge"), Instant.now(), Instant.now(), null);
            var good = node("n2", NodeStatus.ONLINE, 0.2, 8192, 1000, 0, Set.of());
            var antiReq = new InstanceRequest(
                    "lobby", 512, 0.0, 0, 30000, 30010, List.of(), List.of("zone=edge"), "", Map.of());

            var reasons = selector.explainIneligibility(antiReq, List.of(tainted, good));
            assertEquals(Set.of("n1"), reasons.keySet(), "eligible node n2 must be omitted");
            assertTrue(reasons.get("n1").contains("anti-affinity"), reasons.get("n1"));
        }

        @Test
        @DisplayName("an all-eligible node list yields an empty map")
        void eligibleOmitted() {
            var good = node("n1", NodeStatus.ONLINE, 0.2, 8192, 1000, 0, Set.of());
            assertTrue(selector.explainIneligibility(request, List.of(good)).isEmpty());
        }
    }
}
