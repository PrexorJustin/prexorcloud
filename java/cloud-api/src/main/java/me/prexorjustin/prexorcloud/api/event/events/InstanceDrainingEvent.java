package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Fired when a server instance enters the DRAINING state — it is no longer
 * accepting new players but existing players may still be connected.
 */
public record InstanceDrainingEvent(String instanceId, String group, String nodeId) implements CloudEvent {

    @Override
    public String type() {
        return "INSTANCE_DRAINING";
    }
}
