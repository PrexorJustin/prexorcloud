package me.prexorjustin.prexorcloud.modules.example.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;

/**
 * Strongly-typed payload for {@link PlaytimeEventNames#SESSION_END}.
 *
 * <p>STEP 5 — Mirrors {@link SessionStart}. Plugins compute {@code durationMs}
 * themselves from the server-side join timestamp so that the controller never
 * needs to reconcile clocks across nodes.
 */
public record SessionEnd(UUID sessionId, Instant quitAt, long durationMs) {

    public static SessionEnd of(CustomCloudEvent event) {
        Map<String, Object> p = event.payload();
        return new SessionEnd(
                UUID.fromString((String) p.get("sessionId")),
                Instant.parse((String) p.get("quitAt")),
                ((Number) p.get("durationMs")).longValue());
    }

    public CustomCloudEvent toEvent(String source) {
        return new CustomCloudEvent(
                PlaytimeEventNames.SESSION_END,
                source,
                Map.of(
                        "sessionId", sessionId.toString(),
                        "quitAt", quitAt.toString(),
                        "durationMs", durationMs));
    }
}
