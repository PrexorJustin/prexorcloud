package me.prexorjustin.prexorcloud.modules.example.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.modules.example.data.PlaytimeRepository;
import me.prexorjustin.prexorcloud.modules.example.data.Session;
import me.prexorjustin.prexorcloud.modules.example.data.TopEntry;

/**
 * Default {@link PlaytimeQueryService} implementation — a thin wrapper over the
 * repository.
 *
 * <p>STEP 7a — Keeping the service impl separate from the repository lets us
 * expose a narrower, read-only contract to consumers while the module itself
 * still uses the full repository for writes.
 */
public final class PlaytimeQueryServiceImpl implements PlaytimeQueryService {

    private final PlaytimeRepository repo;

    public PlaytimeQueryServiceImpl(PlaytimeRepository repo) {
        this.repo = repo;
    }

    @Override
    public long totalMs(UUID playerId) {
        return repo.totalFor(playerId).map(TopEntry::totalMs).orElse(0L);
    }

    @Override
    public List<TopEntry> top(int limit) {
        return repo.top(limit);
    }

    @Override
    public Optional<Session> latest(UUID playerId) {
        var recent = repo.recentSessions(playerId, 1);
        return recent.isEmpty() ? Optional.empty() : Optional.of(recent.get(0));
    }
}
