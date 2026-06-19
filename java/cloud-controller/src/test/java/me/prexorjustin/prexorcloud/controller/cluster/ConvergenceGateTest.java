package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Pure-logic tests for the observation-phase gate (no Mongo; controllable clock + expected-node set). */
final class ConvergenceGateTest {

    private final AtomicReference<Set<String>> expected = new AtomicReference<>(Set.of());
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private ConvergenceGate gate(Duration grace) {
        return new ConvergenceGate(expected::get, grace, nanos::get);
    }

    @Test
    void noNodesRegistered_opensImmediately() {
        expected.set(Set.of());
        var gate = gate(Duration.ofSeconds(30));
        gate.beginObservation();
        assertFalse(gate.isObserving(), "nothing to observe when no nodes are registered");
        assertTrue(gate.canScaleReconcile());
    }

    @Test
    void blocksUntilAllExpectedNodesReport() {
        expected.set(Set.of("n1", "n2"));
        var gate = gate(Duration.ofSeconds(30));
        gate.beginObservation();

        assertTrue(gate.isObserving());
        assertFalse(gate.canScaleReconcile(), "scale-reconcile blocked while nodes are unreported");

        gate.nodeReported("n1");
        assertFalse(gate.canScaleReconcile(), "still blocked with one node outstanding");

        gate.nodeReported("n2");
        assertTrue(gate.canScaleReconcile(), "gate opens once every expected node has reported");
        assertFalse(gate.isObserving());
    }

    @Test
    void graceWindowExpiryOpensGateEvenIfNodesNeverReport() {
        expected.set(Set.of("n1", "n2"));
        var gate = gate(Duration.ofSeconds(30));
        gate.beginObservation();
        assertFalse(gate.canScaleReconcile());

        // Just inside the window — still blocked.
        nanos.addAndGet(Duration.ofSeconds(30).toNanos() - 1);
        assertFalse(gate.canScaleReconcile());

        // Past the grace window — the unreported nodes are treated as down, gate opens.
        nanos.addAndGet(2);
        assertTrue(gate.canScaleReconcile());
        assertFalse(gate.isObserving());
        assertTrue(gate.lastObservationDurationMs() >= 0);
    }

    @Test
    void unreportedNodeReportingLateStillCountsBeforeGrace() {
        expected.set(Set.of("n1"));
        var gate = gate(Duration.ofSeconds(30));
        gate.beginObservation();
        assertFalse(gate.canScaleReconcile());
        gate.nodeReported("n1");
        assertTrue(gate.canScaleReconcile());
    }

    @Test
    void reportsForUnexpectedNodesAreIgnoredForExitDecision() {
        expected.set(Set.of("n1", "n2"));
        var gate = gate(Duration.ofSeconds(30));
        gate.beginObservation();
        gate.nodeReported("n1");
        gate.nodeReported("n9"); // a node that registered after acquisition — doesn't satisfy expected n2
        assertFalse(gate.canScaleReconcile());
        gate.nodeReported("n2");
        assertTrue(gate.canScaleReconcile());
    }

    @Test
    void reAcquisitionReObserves() {
        expected.set(Set.of("n1"));
        var gate = gate(Duration.ofSeconds(30));
        gate.beginObservation();
        gate.nodeReported("n1");
        assertTrue(gate.canScaleReconcile());

        // A later re-acquisition (e.g. lost then regained leadership) must observe afresh.
        gate.beginObservation();
        assertTrue(gate.isObserving());
        assertFalse(gate.canScaleReconcile());
        gate.nodeReported("n1");
        assertTrue(gate.canScaleReconcile());
    }

    @Test
    void resetStopsObserving() {
        expected.set(Set.of("n1"));
        var gate = gate(Duration.ofSeconds(30));
        gate.beginObservation();
        assertTrue(gate.isObserving());
        gate.reset();
        assertFalse(gate.isObserving());
        assertTrue(gate.canScaleReconcile());
    }

    @Test
    void expectedSetIsSnapshotAtAcquisition() {
        expected.set(Set.of("n1", "n2"));
        var gate = gate(Duration.ofSeconds(30));
        gate.beginObservation();
        // Nodes registered after acquisition must not extend the wait set.
        expected.set(Set.of("n1", "n2", "n3"));
        gate.nodeReported("n1");
        gate.nodeReported("n2");
        assertTrue(gate.canScaleReconcile(), "only the set snapshotted at acquisition gates the phase");
    }
}
