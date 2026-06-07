package me.prexorjustin.prexorcloud.api.event.events;

import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Emitted when an {@code EventChoreography} overlay enters its active window. */
public record ChoreographyOverlayActivatedEvent(String eventName, String group, Instant activeUntil)
        implements CloudEvent {

    @Override
    public String type() {
        return "CHOREOGRAPHY_OVERLAY_ACTIVATED";
    }
}
