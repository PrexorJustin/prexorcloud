package me.prexorjustin.prexorcloud.api.event.events;

import java.util.UUID;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a player connects to any instance in the network. */
public record PlayerConnectedEvent(UUID uuid, String name, String instanceId, String group) implements CloudEvent {

    @Override
    public String type() {
        return "PLAYER_CONNECTED";
    }
}
