package me.prexorjustin.prexorcloud.api.event.events;

import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a node drain completes successfully. */
public record NodeDrainCompletedEvent(String nodeId, Instant timestamp) implements CloudEvent {

    @Override
    public String type() {
        return "NODE_DRAIN_COMPLETED";
    }
}
