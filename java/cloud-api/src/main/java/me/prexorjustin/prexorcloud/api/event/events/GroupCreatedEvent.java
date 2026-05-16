package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a new server group is created. */
public record GroupCreatedEvent(String groupName) implements CloudEvent {

    @Override
    public String type() {
        return "GROUP_CREATED";
    }
}
