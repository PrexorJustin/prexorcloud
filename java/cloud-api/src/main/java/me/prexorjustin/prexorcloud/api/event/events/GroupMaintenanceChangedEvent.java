package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when maintenance mode is toggled for a specific server group. */
public record GroupMaintenanceChangedEvent(String groupName, boolean maintenance, String message)
        implements CloudEvent {

    @Override
    public String type() {
        return "GROUP_MAINTENANCE_CHANGED";
    }
}
