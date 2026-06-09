package me.prexorjustin.prexorcloud.api.event.events;

import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Fired when a node's heartbeat misses the configured threshold for the first
 * time, before it transitions to {@code UNREACHABLE}. Subsequent missed
 * heartbeats on the same session do not re-emit; the event re-fires only after
 * a {@link NodeHeartbeatResumedEvent} clears the stale state.
 */
public record NodeHeartbeatStaleEvent(String nodeId, int missedPongs, Instant lastHeartbeatAt) implements CloudEvent {

    @Override
    public String type() {
        return "NODE_HEARTBEAT_STALE";
    }
}
