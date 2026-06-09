package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when global network maintenance mode is toggled. */
public record MaintenanceUpdatedEvent(boolean globalEnabled, String message) implements CloudEvent {

    @Override
    public String type() {
        return "MAINTENANCE_UPDATED";
    }
}
