package me.prexorjustin.prexorcloud.controller.cluster.state;

import java.time.Instant;

/**
 * A controller participating in the Raft group. {@code raftAddr} is the address
 * Ratis members use to talk to each other; {@code restAddr}/{@code gRPCAddr}
 * are advertised to other tooling (dashboards, daemons) that want to reach this
 * controller directly.
 */
public record Member(
        String nodeId,
        String raftAddr,
        String restAddr,
        String gRPCAddr,
        String label,
        Instant joinedAt,
        Instant lastSeen) {}
