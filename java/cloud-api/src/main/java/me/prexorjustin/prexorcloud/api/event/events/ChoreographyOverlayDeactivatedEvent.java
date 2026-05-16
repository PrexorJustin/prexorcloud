package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Emitted when an {@code EventChoreography} overlay leaves its active window
 * — either because the duration expired or another overlay for the same group
 * superseded it.
 */
public record ChoreographyOverlayDeactivatedEvent(String eventName, String group, String reason) implements CloudEvent {

    @Override
    public String type() {
        return "CHOREOGRAPHY_OVERLAY_DEACTIVATED";
    }
}
