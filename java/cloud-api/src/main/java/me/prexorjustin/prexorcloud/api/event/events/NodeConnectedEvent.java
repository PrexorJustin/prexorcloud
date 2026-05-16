package me.prexorjustin.prexorcloud.api.event.events;

import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a daemon node establishes a connection to the controller. */
public record NodeConnectedEvent(String nodeId, String sessionId, Instant timestamp) implements CloudEvent {

    @Override
    public String type() {
        return "NODE_CONNECTED";
    }
}
