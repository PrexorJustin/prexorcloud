package me.prexorjustin.prexorcloud.api.event.events;

import java.util.UUID;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a player disconnects from the network. */
public record PlayerDisconnectedEvent(UUID uuid, String name, String instanceId, String group) implements CloudEvent {

    @Override
    public String type() {
        return "PLAYER_DISCONNECTED";
    }
}
