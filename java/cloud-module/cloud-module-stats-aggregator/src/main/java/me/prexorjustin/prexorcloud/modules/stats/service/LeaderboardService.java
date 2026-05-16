package me.prexorjustin.prexorcloud.modules.stats.service;

import java.util.List;

import me.prexorjustin.prexorcloud.modules.stats.data.GroupStat;
import me.prexorjustin.prexorcloud.modules.stats.data.PlayerStat;
import me.prexorjustin.prexorcloud.modules.stats.data.StatsRepository;

public final class LeaderboardService {

    private final StatsRepository repo;

    public LeaderboardService(StatsRepository repo) {
        this.repo = repo;
    }

    public List<PlayerStat> topPlayers(int limit) {
        return repo.topPlayers(Math.max(1, limit));
    }

    public List<GroupStat> topGroups(int limit) {
        return repo.topGroups(Math.max(1, limit));
    }
}
