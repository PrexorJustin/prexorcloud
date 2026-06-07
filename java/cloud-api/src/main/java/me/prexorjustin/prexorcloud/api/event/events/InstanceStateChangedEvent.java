package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a server instance transitions to a new lifecycle state. */
public record InstanceStateChangedEvent(
        String instanceId, String group, String nodeId, InstanceState oldState, InstanceState newState)
        implements CloudEvent {

    @Override
    public String type() {
        return "INSTANCE_STATE_CHANGED";
    }
}
