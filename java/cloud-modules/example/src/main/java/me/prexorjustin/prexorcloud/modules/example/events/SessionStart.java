package me.prexorjustin.prexorcloud.modules.example.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;

/**
 * Strongly-typed payload for {@link PlaytimeEventNames#SESSION_START}.
 *
 * <p>STEP 5 — Events on the bus are {@link CustomCloudEvent} instances whose
 * {@code payload} is a loose {@code Map<String,Object>}. We wrap that loose map
 * behind {@link #of(CustomCloudEvent)} / {@link #toEvent(String)} so that both
 * publishers and subscribers inside the module talk in a single record type and
 * don't sprinkle string keys across the codebase.
 */
public record SessionStart(UUID playerId, UUID sessionId, String serverName, Instant joinAt) {

    public static SessionStart of(CustomCloudEvent event) {
        Map<String, Object> p = event.payload();
        return new SessionStart(
                UUID.fromString((String) p.get("playerId")),
                UUID.fromString((String) p.get("sessionId")),
                (String) p.get("serverName"),
                Instant.parse((String) p.get("joinAt")));
    }

    public CustomCloudEvent toEvent(String source) {
        return new CustomCloudEvent(
                PlaytimeEventNames.SESSION_START,
                source,
                Map.of(
                        "playerId", playerId.toString(),
                        "sessionId", sessionId.toString(),
                        "serverName", serverName,
                        "joinAt", joinAt.toString()));
    }
}
