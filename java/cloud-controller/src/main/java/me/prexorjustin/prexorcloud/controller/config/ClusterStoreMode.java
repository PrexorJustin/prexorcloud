package me.prexorjustin.prexorcloud.controller.config;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Selects the backing store for cluster control-plane state during the single-writer rewrite's
 * Phase-4 migration. Transitional — removed together with embedded Raft at the cutover.
 *
 * <ul>
 *   <li>{@code RAFT} (default) — Raft is the sole store; no Mongo mirror. Zero behavior change.
 *   <li>{@code DUAL} — Raft stays authoritative, but every committed entry is also mirrored into the
 *       Mongo cluster store. This is the dual-write soak that proves Mongo matches Raft under load
 *       before the read cutover.
 *   <li>{@code MONGO} — intended end state (read + write from Mongo). The read cutover is not wired
 *       yet, so today this behaves like {@code DUAL} and bootstrap logs a warning.
 * </ul>
 */
public enum ClusterStoreMode {
    RAFT,
    DUAL,
    MONGO;

    /** Lenient parse so operators can write {@code clusterStore: dual} (any case) in controller.yml. */
    @JsonCreator
    public static ClusterStoreMode fromString(String value) {
        return value == null || value.isBlank() ? RAFT : valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /** True when committed Raft entries should be mirrored into the Mongo cluster store. */
    public boolean mirrorsToMongo() {
        return this != RAFT;
    }

    /** True when reads should be served from Mongo — the cutover, not implemented yet. */
    public boolean readsFromMongo() {
        return this == MONGO;
    }
}
