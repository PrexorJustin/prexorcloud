package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;
import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired whenever a {@link PlayerJourneyEntry} is appended to the journey log. */
public record PlayerJourneyEvent(PlayerJourneyEntry entry) implements CloudEvent {

    @Override
    public String type() {
        return "PLAYER_JOURNEY";
    }
}
