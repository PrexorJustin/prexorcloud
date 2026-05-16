package me.prexorjustin.prexorcloud.api.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One observed event in a player's session as recorded by the controller.
 *
 * <p>This is a raw, append-only audit row — not an interpreted state machine.
 * {@code eventType} mirrors the source {@code CloudEvent#type()} when one
 * exists ({@code PLAYER_CONNECTED}, {@code PLAYER_TRANSFER},
 * {@code PLAYER_DISCONNECTED}) and uses {@code INSTANCE_CRASHED} for the
 * per-player projection of an instance crash. Modules that want a typed
 * stage/reason vocabulary (lobby/game/queue/match) own that interpretation
 * themselves on top of this log.
 *
 * @param playerUuid     player's UUID
 * @param playerName     player's display name at the time of the event
 * @param eventType      the source event type
 * @param fromInstanceId backend instance the player came from (empty when not applicable)
 * @param toInstanceId   backend instance the player is on after the event (empty when not applicable)
 * @param group          the group of the player's current backend instance (empty when not applicable)
 * @param timestamp      when the event was observed
 */
public record PlayerJourneyEntry(
        UUID playerUuid,
        String playerName,
        String eventType,
        String fromInstanceId,
        String toInstanceId,
        String group,
        Instant timestamp) {

    public PlayerJourneyEntry {
        if (playerUuid == null) throw new IllegalArgumentException("playerUuid");
        if (playerName == null) playerName = "";
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType");
        if (fromInstanceId == null) fromInstanceId = "";
        if (toInstanceId == null) toInstanceId = "";
        if (group == null) group = "";
        if (timestamp == null) throw new IllegalArgumentException("timestamp");
    }
}
