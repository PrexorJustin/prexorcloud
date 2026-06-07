package me.prexorjustin.prexorcloud.api.event.events;

import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a node drain is requested. */
public record NodeDrainRequestedEvent(
        String nodeId, boolean shutdownAfterDrain, int drainTimeoutSeconds, String kickMessage, Instant timestamp)
        implements CloudEvent {

    @Override
    public String type() {
        return "NODE_DRAIN_REQUESTED";
    }
}
