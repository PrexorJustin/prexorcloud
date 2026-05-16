package me.prexorjustin.prexorcloud.modules.example.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One completed (or in-progress) player session.
 *
 * <p>STEP 3 — This is a plain Jackson-serialisable record. {@link
 * me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore} will round-trip
 * it through Jackson automatically — no manual mapping required.
 *
 * <p>{@code quitAt} and {@code durationMs} are null / zero while the session is
 * still open; {@link PlaytimeRepository#closeSession} fills them in on
 * PLAYTIME:SESSION_END.
 */
public record Session(
        UUID playerId, UUID sessionId, Instant joinAt, Instant quitAt, long durationMs, String serverName) {}
