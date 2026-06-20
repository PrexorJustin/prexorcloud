package me.prexorjustin.prexorcloud.daemon.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.prexorjustin.prexorcloud.daemon.config.ControllerEndpoint;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the daemon's controller seed-list rotation: the policy that picks which controller to
 * dial next as connections fail and succeed. Exercises the pure decision (advance-on-non-ack,
 * no-advance-on-ack, single-candidate no-op, redirect-then-fail) without real sockets.
 */
final class DaemonGrpcClientRotationTest {

    private static ControllerEndpoint ep(String host) {
        return new ControllerEndpoint(host, 9090);
    }

    @Test
    void advancesToNextSeedAfterAFailedDial() {
        var c = DaemonGrpcClient.unshuffledForTest(List.of(ep("a"), ep("b"), ep("c")));
        var first = c.selectTarget(); // initial connect: nothing pending
        assertEquals("a", c.controllerHost());

        c.simulateDisconnectForTest(); // never reached an ACK → schedule a rotation
        assertTrue(c.advancePendingForTest());
        var second = c.selectTarget(); // consumes the flag and advances
        assertNotEquals(first, second);
        assertFalse(c.advancePendingForTest());
    }

    @Test
    void rotatesThroughEverySeedThenWrapsAround() {
        var c = DaemonGrpcClient.unshuffledForTest(List.of(ep("a"), ep("b"), ep("c")));
        Set<String> visited = new HashSet<>();
        visited.add(c.controllerHost());
        for (int i = 0; i < 2; i++) {
            c.simulateDisconnectForTest();
            c.selectTarget();
            visited.add(c.controllerHost());
        }
        assertEquals(Set.of("a", "b", "c"), visited, "rotation visits every seed within one cycle");

        String beforeWrap = c.controllerHost();
        c.simulateDisconnectForTest();
        c.selectTarget();
        assertEquals("a", c.controllerHost(), "after a full cycle rotation wraps to the first seed");
        assertNotEquals(beforeWrap, c.controllerHost());
    }

    @Test
    void doesNotAdvanceAfterASuccessfulConnectThenDrop() {
        var c = DaemonGrpcClient.unshuffledForTest(List.of(ep("a"), ep("b")));
        c.selectTarget();
        assertEquals("a", c.controllerHost());

        c.markConnectedForTest(); // handshake acknowledged
        c.simulateDisconnectForTest(); // dropped AFTER connecting → keep the same target for one retry
        assertFalse(c.advancePendingForTest());
        c.selectTarget();
        assertEquals("a", c.controllerHost(), "a post-ACK drop retries the same target first");
    }

    @Test
    void singleCandidateRotationIsANoOp() {
        var c = DaemonGrpcClient.unshuffledForTest(List.of(ep("solo")));
        c.simulateDisconnectForTest();
        c.selectTarget();
        assertEquals("solo", c.controllerHost());
        c.simulateDisconnectForTest();
        c.selectTarget();
        assertEquals("solo", c.controllerHost());
    }

    @Test
    void mergesAdvertisedControllersAppendingNewDeduplicatingAndKeepingTargetAnchored() {
        var c = DaemonGrpcClient.unshuffledForTest(List.of(ep("a"), ep("b")));
        assertEquals("a", c.controllerHost());

        // Cluster advertises an overlapping + a new + a loopback address.
        c.mergeAdvertisedControllers(List.of("a:9090", "ctrl-3:9090", "127.0.0.1:9090"));

        // Current dial target is untouched; the new routable address joined the rotation, the
        // duplicate and the loopback were dropped.
        assertEquals("a", c.controllerHost(), "merge must not change the current dial target");
        Set<String> visited = new HashSet<>();
        visited.add(c.controllerHost());
        for (int i = 0; i < 2; i++) {
            c.simulateDisconnectForTest();
            c.selectTarget();
            visited.add(c.controllerHost());
        }
        assertEquals(Set.of("a", "b", "ctrl-3"), visited, "ctrl-3 joined the rotation; a/loopback not duplicated");
    }

    @Test
    void firesLearnedControllersListenerOnlyWhenAdvertisedSetChanges() {
        var c = DaemonGrpcClient.unshuffledForTest(List.of(ep("a")));
        var captured = new java.util.ArrayList<List<ControllerEndpoint>>();
        c.setLearnedControllersListener(captured::add);

        c.mergeAdvertisedControllers(List.of("a:9090", "b:9090"));
        c.mergeAdvertisedControllers(List.of("a:9090", "b:9090")); // identical → no new notification
        assertEquals(1, captured.size(), "listener fires once per distinct advertised set");
        assertEquals(List.of(ep("a"), ep("b")), captured.get(0));

        c.mergeAdvertisedControllers(List.of("a:9090", "b:9090", "ctrl-3:9090")); // changed → fires again
        assertEquals(2, captured.size());
    }

    @Test
    void mergeIgnoresEmptyAndNullAdvertisements() {
        var c = DaemonGrpcClient.unshuffledForTest(List.of(ep("a")));
        c.mergeAdvertisedControllers(null);
        c.mergeAdvertisedControllers(List.of());
        c.simulateDisconnectForTest();
        c.selectTarget();
        assertEquals("a", c.controllerHost(), "no advertised members → single candidate unchanged");
    }

    @Test
    void redirectPinsLeaderThenRotatesOffItWhenItFails() {
        var c = DaemonGrpcClient.unshuffledForTest(List.of(ep("a"), ep("b")));
        assertTrue(c.redirectToLeader("leader:9099"));
        assertEquals("leader", c.controllerHost());
        assertEquals(9099, c.controllerPort());
        assertFalse(c.advancePendingForTest(), "a redirect must not itself schedule a rotation");

        // The leader we were redirected to is unreachable: a failed dial must rotate back to a seed,
        // where a follower can re-redirect us to the *new* leader.
        c.simulateDisconnectForTest();
        assertTrue(c.advancePendingForTest());
        c.selectTarget();
        assertNotEquals("leader", c.controllerHost(), "a dead redirect leader rotates back to a seed");
    }
}
