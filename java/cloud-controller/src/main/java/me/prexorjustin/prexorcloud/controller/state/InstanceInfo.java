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
        int deploymentRevision) {

    public InstanceInfo(
            String id,
            String group,
            String nodeId,
            InstanceState state,
            int port,
            int playerCount,
            long uptimeMs,
            Instant startedAt) {
        this(id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, 0);
    }

    public InstanceInfo withState(InstanceState newState) {
        return new InstanceInfo(
                id, group, nodeId, newState, port, playerCount, uptimeMs, startedAt, deploymentRevision);
    }

    public InstanceInfo withStatus(InstanceState state, int playerCount, long uptimeMs) {
        return new InstanceInfo(id, group, nodeId, state, port, playerCount, uptimeMs, startedAt, deploymentRevision);
    }
}
