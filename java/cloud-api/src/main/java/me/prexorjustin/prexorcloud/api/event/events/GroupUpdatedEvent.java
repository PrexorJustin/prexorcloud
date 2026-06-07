package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a server group's configuration is updated. */
public record GroupUpdatedEvent(String groupName) implements CloudEvent {

    @Override
    public String type() {
        return "GROUP_UPDATED";
    }
}
