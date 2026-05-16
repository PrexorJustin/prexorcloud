package me.prexorjustin.prexorcloud.modules.journey.data;

import java.time.Instant;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;

/**
 * Mongo-side projection of {@link PlayerJourneyEntry}. Field order matches the
 * compound index {@code (playerUuid, timestamp DESC)} declared by
 * {@link JourneyRepository}. {@code playerUuid} is stored as its string form
 * for human-readable inspection in shells; we never sort or scan on it raw.
 */
public record JourneyDoc(
        String playerUuid,
        String playerName,
        String eventType,
        String fromInstanceId,
        String toInstanceId,
        String group,
        Instant timestamp) {

    public static JourneyDoc from(PlayerJourneyEntry entry) {
        return new JourneyDoc(
                entry.playerUuid().toString(),
                entry.playerName(),
                entry.eventType(),
                entry.fromInstanceId(),
                entry.toInstanceId(),
                entry.group(),
                entry.timestamp());
    }

    public PlayerJourneyEntry toEntry() {
        return new PlayerJourneyEntry(
                UUID.fromString(playerUuid),
                playerName,
                eventType,
                fromInstanceId,
                toInstanceId,
                group,
                timestamp);
    }
}
