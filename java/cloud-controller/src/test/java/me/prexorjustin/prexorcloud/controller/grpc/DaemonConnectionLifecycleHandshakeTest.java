package me.prexorjustin.prexorcloud.controller.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.controller.grpc.DaemonConnectionLifecycle.ReportedInstanceAction;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.HandshakeAck;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

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

    @Test
    void advertisesLiveControllerAddressesFilteringBlanks() {
        var ack = HandshakeAck.newBuilder();
        DaemonConnectionLifecycle.applyControllerAddrs(ack, () -> List.of("10.0.0.3:9090", "", "10.0.0.6:9090"));
        assertEquals(List.of("10.0.0.3:9090", "10.0.0.6:9090"), ack.build().getControllerGrpcAddrsList());
    }

    @Test
    void advertisesNothingWhenNoMembersKnown() {
        var ack = HandshakeAck.newBuilder();
        DaemonConnectionLifecycle.applyControllerAddrs(ack, List::of);
        assertEquals(0, ack.build().getControllerGrpcAddrsCount());
    }

    // --- adopt-vs-reap policy for a daemon-reported running instance (transparent-failover fix) ---

    private static InstanceInfo on(String nodeId) {
        return new InstanceInfo("lobby-2", "lobby", nodeId, InstanceState.RUNNING, 30000, 0, 0, Instant.EPOCH);
    }

    @Test
    void adoptsUnknownRunningInstanceWhenGroupExists() {
        // The crux of transparent failover: a running instance we don't know about is NOT killed.
        assertEquals(
                ReportedInstanceAction.ADOPT,
                DaemonConnectionLifecycle.decideReportedInstance(Optional.empty(), "node-1", true));
    }

    @Test
    void reapsUnknownRunningInstanceOnlyWhenGroupIsGone() {
        assertEquals(
                ReportedInstanceAction.REAP,
                DaemonConnectionLifecycle.decideReportedInstance(Optional.empty(), "node-1", false));
    }

    @Test
    void updatesKnownInstanceReportedByItsOwnNode() {
        assertEquals(
                ReportedInstanceAction.UPDATE,
                DaemonConnectionLifecycle.decideReportedInstance(Optional.of(on("node-1")), "node-1", true));
    }

    @Test
    void skipsKnownInstanceReportedByAnotherNode() {
        assertEquals(
                ReportedInstanceAction.SKIP_WRONG_NODE,
                DaemonConnectionLifecycle.decideReportedInstance(Optional.of(on("node-1")), "node-2", true));
    }

    @Test
    void resurrectsKnownTerminalInstanceTheDaemonStillRuns() {
        // Record went CRASHED (e.g. a transient blip) but the daemon reports it running — re-adopt it
        // rather than leave a live server stuck dead (a plain UPDATE would hit the terminal guard).
        var crashed = new InstanceInfo("lobby-2", "lobby", "node-1", InstanceState.CRASHED, 30000, 0, 0, Instant.EPOCH);
        assertEquals(
                ReportedInstanceAction.ADOPT,
                DaemonConnectionLifecycle.decideReportedInstance(Optional.of(crashed), "node-1", true));
    }

    @Test
    void reapsKnownTerminalInstanceWhoseGroupIsGone() {
        var stopped = new InstanceInfo("lobby-2", "lobby", "node-1", InstanceState.STOPPED, 30000, 0, 0, Instant.EPOCH);
        assertEquals(
                ReportedInstanceAction.REAP,
                DaemonConnectionLifecycle.decideReportedInstance(Optional.of(stopped), "node-1", false));
    }
}
