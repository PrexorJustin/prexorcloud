package me.prexorjustin.prexorcloud.api.event.events;

import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Fired when a previously-stale node delivers a fresh pong, ending the
 * heartbeat-stale window. Paired with {@link NodeHeartbeatStaleEvent}; only
 * emitted if a stale event was emitted first.
 */
public record NodeHeartbeatResumedEvent(String nodeId, Instant lastHeartbeatAt) implements CloudEvent {

    @Override
    public String type() {
        return "NODE_HEARTBEAT_RESUMED";
    }
}
