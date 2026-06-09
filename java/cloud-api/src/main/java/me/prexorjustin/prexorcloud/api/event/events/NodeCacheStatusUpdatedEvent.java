package me.prexorjustin.prexorcloud.api.event.events;

import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a node's cache status is updated. */
public record NodeCacheStatusUpdatedEvent(String nodeId, long totalSizeBytes, Instant timestamp) implements CloudEvent {

    @Override
    public String type() {
        return "NODE_CACHE_STATUS";
    }
}
