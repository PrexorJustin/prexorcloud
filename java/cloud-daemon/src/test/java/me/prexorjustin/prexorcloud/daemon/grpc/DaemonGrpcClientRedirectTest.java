package me.prexorjustin.prexorcloud.daemon.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the daemon's leader-redirect target swap (Phase 3). A follower's {@code HandshakeAck}
 * carries the leader's gRPC address; {@link DaemonGrpcClient#redirectToLeader} parses it and swaps the
 * dial target. With no reconnect manager set, no network reconnect is attempted — these exercise the
 * pure parse/swap/decision; the actual reconnect is integration (needs a live controller).
 */
final class DaemonGrpcClientRedirectTest {

    private static DaemonGrpcClient client() {
        // No reconnect manager / ssl / resource monitor / dispatcher — redirectToLeader only swaps the
        // target and skips the reconnect when no manager is set, so none are dereferenced.
        return new DaemonGrpcClient("ctrl-a", 9090, "node-1", "", 1024, Map.of(), null, null, null);
    }

    @Test
    void redirectsToANewLeaderTarget() {
        var c = client();
        assertTrue(c.redirectToLeader("10.0.0.7:9091"));
        assertEquals("10.0.0.7", c.controllerHost());
        assertEquals(9091, c.controllerPort());
    }

    @Test
    void sameTargetIsNotARedirect() {
        var c = client();
        assertFalse(c.redirectToLeader("ctrl-a:9090"), "redirect to the current target is a no-op");
        assertEquals("ctrl-a", c.controllerHost());
        assertEquals(9090, c.controllerPort());
    }

    @Test
    void invalidAddressesAreIgnoredAndLeaveTargetUnchanged() {
        var c = client();
        assertFalse(c.redirectToLeader(null));
        assertFalse(c.redirectToLeader(""));
        assertFalse(c.redirectToLeader("nohostport"));
        assertFalse(c.redirectToLeader("host:notaport"));
        assertFalse(c.redirectToLeader("host:0"));
        assertFalse(c.redirectToLeader("host:99999"));
        assertFalse(c.redirectToLeader("host:"));
        assertEquals("ctrl-a", c.controllerHost(), "target unchanged after invalid redirects");
        assertEquals(9090, c.controllerPort());
    }
}
