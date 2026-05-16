package me.prexorjustin.prexorcloud.api.event.events;

import java.util.UUID;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a player is transferred between instances. */
public record PlayerTransferEvent(UUID uuid, String name, String fromInstanceId, String toInstanceId)
        implements CloudEvent {

    @Override
    public String type() {
        return "PLAYER_TRANSFER";
    }
}
