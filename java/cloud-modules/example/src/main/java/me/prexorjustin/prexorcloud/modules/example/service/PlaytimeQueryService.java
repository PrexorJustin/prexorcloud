package me.prexorjustin.prexorcloud.modules.example.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.module.service.ServiceKey;
import me.prexorjustin.prexorcloud.modules.example.data.Session;
import me.prexorjustin.prexorcloud.modules.example.data.TopEntry;

/**
 * Internal read-only service for querying playtime.
 *
 * <p>The platform capability published by {@code ExamplePlatformModule} adapts
 * {@link #totalMs(UUID)} to a parent-loaded {@code ToLongFunction<UUID>} handle
 * so isolated module classloaders do not need to share this module-local
 * interface.
 *
 * <p>Keep service interfaces narrow: only the read-shape other modules need.
 * Leave writes and lifecycle behind the publishing module's own event handlers
 * so consumers can't invalidate cache / ordering invariants.
 */
public interface PlaytimeQueryService {

    /** Stable service identity for callers that prefer typed local keys. */
    ServiceKey<PlaytimeQueryService> KEY = ServiceKey.of(PlaytimeQueryService.class);

    long totalMs(UUID playerId);

    List<TopEntry> top(int limit);

    Optional<Session> latest(UUID playerId);
}
