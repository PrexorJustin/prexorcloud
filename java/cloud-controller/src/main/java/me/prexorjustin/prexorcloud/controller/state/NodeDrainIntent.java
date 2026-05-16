package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.Set;

public record NodeDrainIntent(
        String nodeId,
        boolean shutdownAfterDrain,
        String kickMessage,
        Instant requestedAt,
        Instant timeoutAt,
        Set<String> drainingInstanceIds) {

    public NodeDrainIntent withDrainingInstanceIds(Set<String> updatedInstanceIds) {
        return new NodeDrainIntent(nodeId, shutdownAfterDrain, kickMessage, requestedAt, timeoutAt, updatedInstanceIds);
    }
}
