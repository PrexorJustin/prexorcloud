package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Guards the single-writer placement invariants in {@link NodeRegistry}: concurrent
 * reservations must never claim the same port or clobber each other's memory, and a
 * daemon telemetry heartbeat must never recycle a still-held reservation.
 */
class NodeRegistryReservationTest {

    private NodeRegistry freshNode(String nodeId, long totalMemoryMb) {
        var registry = new NodeRegistry();
        registry.add(nodeId, "10.0.0.1", totalMemoryMb, Map.of(), Instant.now(), null);
        return registry;
    }

    @Test
    void concurrentReservationsClaimDistinctPortsAndStackMemory() throws InterruptedException {
        var registry = freshNode("node-1", 65536);
        int threads = 32;
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var claimedPorts = ConcurrentHashMap.<Integer>newKeySet();
        var failures = ConcurrentHashMap.<String>newKeySet();

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    go.await();
                    // Range exactly fits `threads` ports; every reservation must succeed with a unique one.
                    var reservation = registry.reservePlacement("node-1", 512, 30000, 30000 + threads - 1);
                    if (reservation.isEmpty()) {
                        failures.add("empty reservation");
                    } else if (!claimedPorts.add(reservation.get().port())) {
                        failures.add("duplicate port " + reservation.get().port());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "threads did not start");
        go.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "reservations did not finish");

        assertTrue(failures.isEmpty(), () -> "reservation races: " + failures);
        assertEquals(threads, claimedPorts.size(), "every concurrent reservation must claim a distinct port");
        var node = registry.get("node-1").orElseThrow();
        assertEquals(threads, node.usedPorts().size());
        assertEquals(512L * threads, node.usedMemoryMb(), "memory reservations must stack, none lost");
        assertEquals(threads, node.instanceCount());
    }

    @Test
    void reservationFailsWhenRangeExhausted() {
        var registry = freshNode("node-1", 65536);
        assertTrue(registry.reservePlacement("node-1", 256, 30000, 30000).isPresent());
        // Range held exactly one port; the second reservation must fail rather than reuse it.
        assertTrue(registry.reservePlacement("node-1", 256, 30000, 30000).isEmpty());
    }

    @Test
    void telemetryUnionsReservedPortAndFloorsMemoryWhilePreRunning() {
        var registry = freshNode("node-1", 65536);
        var reservation =
                registry.reservePlacement("node-1", 2048, 30000, 30100).orElseThrow();
        int reservedPort = reservation.port();

        // Daemon heartbeat: it has NOT started the instance yet, so it reports neither the
        // reserved port nor the reserved memory. With a pre-running instance on the node the
        // reservation must survive verbatim.
        registry.applyTelemetry(
                "node-1",
                0.1,
                65536, /*reportedUsedMemory*/
                0,
                50000,
                60000, /*reportedCount*/
                0,
                /*reportedPorts*/ Set.of(), /*controllerInstanceCount*/
                1, /*hasPreRunning*/
                true);

        var node = registry.get("node-1").orElseThrow();
        assertTrue(node.usedPorts().contains(reservedPort), "heartbeat must not recycle a reserved port");
        assertEquals(2048L, node.usedMemoryMb(), "heartbeat must not drop reserved memory while pre-running");
        assertEquals(1, node.instanceCount());
        assertEquals(0.1, node.cpuUsage(), 1e-9, "cpu telemetry is taken from the daemon");
    }

    @Test
    void telemetryFollowsDaemonOnceNothingPreRunning() {
        var registry = freshNode("node-1", 65536);
        registry.reservePlacement("node-1", 2048, 30000, 30100).orElseThrow();

        // Instance is now RUNNING: the daemon reports its real RSS + port, nothing is pre-running.
        // Telemetry takes over so memory self-heals (no permanent reservation leak).
        registry.applyTelemetry(
                "node-1",
                0.2,
                65536, /*reportedUsedMemory*/
                1500,
                50000,
                60000, /*reportedCount*/
                1,
                Set.of(30000), /*controllerInstanceCount*/
                1, /*hasPreRunning*/
                false);

        var node = registry.get("node-1").orElseThrow();
        assertEquals(1500L, node.usedMemoryMb(), "memory follows the daemon once no instance is pre-running");
        assertTrue(node.usedPorts().contains(30000));
    }

    @Test
    void releasePortFreesItForReuseWithoutTouchingMemory() {
        var registry = freshNode("node-1", 65536);
        var reservation =
                registry.reservePlacement("node-1", 2048, 30000, 30100).orElseThrow();
        int port = reservation.port();

        registry.releasePort("node-1", port);

        var node = registry.get("node-1").orElseThrow();
        assertFalse(node.usedPorts().contains(port), "released port must be free for reuse");
        assertEquals(2048L, node.usedMemoryMb(), "releasePort leaves memory to the telemetry tick");
        // The freed port is the lowest free again -> next reservation reclaims it.
        assertEquals(
                port,
                registry.reservePlacement("node-1", 1, 30000, 30100)
                        .orElseThrow()
                        .port());
    }
}
