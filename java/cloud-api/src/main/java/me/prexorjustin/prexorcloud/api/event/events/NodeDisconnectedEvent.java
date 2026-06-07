package me.prexorjustin.prexorcloud.api.event.events;

import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a daemon node disconnects from the controller. */
public record NodeDisconnectedEvent(String nodeId, String reason, Instant timestamp) implements CloudEvent {

    @Override
    public String type() {
        return "NODE_DISCONNECTED";
    }
}
