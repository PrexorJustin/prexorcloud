package me.prexorjustin.prexorcloud.api.event.events;

import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a node reports an updated resource status. */
public record NodeStatusUpdatedEvent(
        String nodeId, double cpuUsage, long usedMemoryMb, long totalMemoryMb, Instant lastHeartbeatAt)
        implements CloudEvent {

    @Override
    public String type() {
        return "NODE_STATUS";
    }
}
