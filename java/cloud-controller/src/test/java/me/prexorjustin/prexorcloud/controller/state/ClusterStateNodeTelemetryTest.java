package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration of the placement-reservation invariants at the {@link ClusterState} level:
 * a daemon NodeStatus heartbeat is applied as telemetry that cannot recycle a
 * controller-held reservation for an instance the daemon has not started yet, and
 * instance teardown frees the reserved port.
 */
class ClusterStateNodeTelemetryTest {

    private EventBus eventBus;
    private ClusterState clusterState;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        clusterState.addNode("node-1", "10.0.0.1", 65536, Map.of(), Instant.now(), null);
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    void heartbeatDoesNotRecycleReservationForUnstartedInstance() {
        var reservation =
                clusterState.reservePlacement("node-1", 2048, 30000, 30100).orElseThrow();
        int port = reservation.port();
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.SCHEDULED, port, 0, 0, Instant.now()));

        // Daemon heartbeat arrives before the instance is started: reports zero usage, no ports.
        clusterState.applyNodeTelemetry("node-1", 0.05, 65536, 0, 50000, 60000, 0, Set.of());

        var node = clusterState.getNode("node-1").orElseThrow();
        assertTrue(node.usedPorts().contains(port), "reserved port must survive the heartbeat");
        assertEquals(2048L, node.usedMemoryMb(), "reserved memory must survive while instance is SCHEDULED");
        assertEquals(1, node.instanceCount(), "instance count floored at the controller's own view");
    }

    @Test
    void heartbeatFollowsDaemonOnceInstanceRunning() {
        var reservation =
                clusterState.reservePlacement("node-1", 2048, 30000, 30100).orElseThrow();
        int port = reservation.port();
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, port, 0, 0, Instant.now()));

        clusterState.applyNodeTelemetry("node-1", 0.3, 65536, 1700, 50000, 60000, 1, Set.of(port));

        var node = clusterState.getNode("node-1").orElseThrow();
        assertEquals(1700L, node.usedMemoryMb(), "running instance memory follows the daemon RSS");
        assertTrue(node.usedPorts().contains(port));
    }

    @Test
    void removeInstanceFreesReservedPort() {
        var reservation =
                clusterState.reservePlacement("node-1", 2048, 30000, 30100).orElseThrow();
        int port = reservation.port();
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, port, 0, 0, Instant.now()));

        clusterState.removeInstance("lobby-1");

        var node = clusterState.getNode("node-1").orElseThrow();
        assertFalse(node.usedPorts().contains(port), "teardown must free the reserved port for reuse");
        // With nothing pre-running, the next heartbeat reconciles memory down to the daemon value.
        clusterState.applyNodeTelemetry("node-1", 0.0, 65536, 0, 50000, 60000, 0, Set.of());
        assertEquals(
                0L, clusterState.getNode("node-1").orElseThrow().usedMemoryMb(), "memory self-heals after teardown");
    }
}
