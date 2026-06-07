package me.prexorjustin.prexorcloud.api.module.capability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;

/**
 * Read-only view over the controller's per-player event log.
 *
 * <p>The log is append-only and contains raw observations
 * ({@code PLAYER_CONNECTED}, {@code PLAYER_TRANSFER}, {@code PLAYER_DISCONNECTED},
 * {@code INSTANCE_CRASHED}). Modules that want typed stage interpretations
 * (queue/match/lobby/game/...) layer that on top of this log in their own
 * storage; they do not write back into the controller's log.
 *
 * <p>The controller registers a built-in implementation as a capability handle
 * with id {@link #CAPABILITY_ID}.
 */
public interface PlayerJourneyTracker {

    String CAPABILITY_ID = "prexor.player.journey";

    /**
     * Most recent entries for a player, newest first.
     *
     * @param playerUuid player to look up
     * @param limit      maximum entries to return; values &lt;= 0 are treated as 1
     */
    List<PlayerJourneyEntry> recent(UUID playerUuid, int limit);

    /** All entries for a player at or after {@code since}, newest first. */
    List<PlayerJourneyEntry> since(UUID playerUuid, Instant since);
}
