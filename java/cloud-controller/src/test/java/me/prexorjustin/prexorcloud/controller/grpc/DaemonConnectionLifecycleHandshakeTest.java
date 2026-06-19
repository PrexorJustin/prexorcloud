package me.prexorjustin.prexorcloud.controller.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.protocol.HandshakeAck;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the leadership fields a controller stamps on a {@link HandshakeAck} (Phase 3
 * redirect/fencing): the leader stamps its fencing epoch; a follower returns the leader's gRPC
 * address so the daemon redirects. Tests the pure decision in isolation — the full handshake path
 * needs ~17 collaborators, so the decision is extracted into {@code applyLeadership}.
 */
final class DaemonConnectionLifecycleHandshakeTest {

    private static Leadership follower() {
        return new Leadership() {
            @Override
            public boolean isLeader() {
                return false;
            }

            @Override
            public long currentEpoch() {
                return 0;
            }
        };
    }

    @Test
    void leaderStampsEpochAndDoesNotRedirect() {
        var ack = HandshakeAck.newBuilder();
        DaemonConnectionLifecycle.applyLeadership(ack, Leadership.alwaysLeader(), () -> "10.0.0.9:50051");
        var built = ack.build();
        assertEquals(1L, built.getEpoch(), "leader stamps its fencing epoch");
        assertEquals("", built.getLeaderGrpcAddr(), "leader never sets a redirect address");
    }

    @Test
    void followerRedirectsToLeaderWithoutStampingEpoch() {
        var ack = HandshakeAck.newBuilder();
        DaemonConnectionLifecycle.applyLeadership(ack, follower(), () -> "10.0.0.3:50051");
        var built = ack.build();
        assertEquals("10.0.0.3:50051", built.getLeaderGrpcAddr(), "follower returns the leader's gRPC address");
        assertEquals(0L, built.getEpoch(), "follower is not the command authority — no epoch");
    }

    @Test
    void followerWithUnknownLeaderDoesNotRedirect() {
        var ack = HandshakeAck.newBuilder();
        DaemonConnectionLifecycle.applyLeadership(ack, follower(), () -> "");
        var built = ack.build();
        assertEquals("", built.getLeaderGrpcAddr(), "no known leader yet → the daemon stays put");
        assertEquals(0L, built.getEpoch());
    }
}
