package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a server group is deleted. */
public record GroupDeletedEvent(String groupName) implements CloudEvent {

    @Override
    public String type() {
        return "GROUP_DELETED";
    }
}
