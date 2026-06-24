package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;

import me.prexorjustin.prexorcloud.protocol.InstanceState;

public record InstanceInfo(
        String id,
        String group,
        String nodeId,
        InstanceState state,
        int port,
        int playerCount,
        long uptimeMs,
        Instant startedAt,
        int deploymentRevision,
        // Latest 1-minute TPS reported by the plugin metrics heartbeat (0 = no data yet / not a game
        // server). Threaded in alongside playerCount so the scheduler can scale on tick health.
        double tps1m) {

    public InstanceInfo(
            String id,
            String group,
            String nodeId,
            InstanceState state,
            int port,
            int playerCount,
            long uptimeMs,
            Instant startedAt) {
        this(id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, 0, 0.0);
    }

    public InstanceInfo(
            String id,
            String group,
            String nodeId,
            InstanceState state,
            int port,
            int playerCount,
            long uptimeMs,
            Instant startedAt,
            int deploymentRevision) {
        this(id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, deploymentRevision, 0.0);
    }

    public InstanceInfo withState(InstanceState newState) {
        return new InstanceInfo(
                id, group, nodeId, newState, port, playerCount, uptimeMs, startedAt, deploymentRevision, tps1m);
    }

    public InstanceInfo withStatus(InstanceState state, int playerCount, long uptimeMs) {
        return new InstanceInfo(
                id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, deploymentRevision, tps1m);
    }

    public InstanceInfo withTps(double tps1m) {
        return new InstanceInfo(
                id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, deploymentRevision, tps1m);
    }
}
