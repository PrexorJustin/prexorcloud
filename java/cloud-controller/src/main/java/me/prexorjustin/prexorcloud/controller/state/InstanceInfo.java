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
        double tps1m,
        // Warm-pool member: fully started (RUNNING) but held back from player routing until promoted.
        // The proxy excludes warm instances from join targets; the scheduler promotes one (warm -> false)
        // instead of cold-starting when a group needs more serving capacity.
        boolean warm) {

    public InstanceInfo(
            String id,
            String group,
            String nodeId,
            InstanceState state,
            int port,
            int playerCount,
            long uptimeMs,
            Instant startedAt) {
        this(id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, 0, 0.0, false);
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
        this(id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, deploymentRevision, 0.0, false);
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
            int deploymentRevision,
            double tps1m) {
        this(id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, deploymentRevision, tps1m, false);
    }

    public InstanceInfo withState(InstanceState newState) {
        return new InstanceInfo(
                id, group, nodeId, newState, port, playerCount, uptimeMs, startedAt, deploymentRevision, tps1m, warm);
    }

    public InstanceInfo withStatus(InstanceState state, int playerCount, long uptimeMs) {
        return new InstanceInfo(
                id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, deploymentRevision, tps1m, warm);
    }

    public InstanceInfo withTps(double tps1m) {
        return new InstanceInfo(
                id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, deploymentRevision, tps1m, warm);
    }

    public InstanceInfo withWarm(boolean warm) {
        return new InstanceInfo(
                id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, deploymentRevision, tps1m, warm);
    }
}
