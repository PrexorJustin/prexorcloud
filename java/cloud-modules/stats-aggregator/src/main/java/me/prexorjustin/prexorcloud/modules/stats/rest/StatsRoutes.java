package me.prexorjustin.prexorcloud.modules.stats.rest;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.stats.config.StatsConfig;
import me.prexorjustin.prexorcloud.modules.stats.data.StatsRepository;
import me.prexorjustin.prexorcloud.modules.stats.metrics.PrometheusExporter;
import me.prexorjustin.prexorcloud.modules.stats.rest.dto.JoinRequest;
import me.prexorjustin.prexorcloud.modules.stats.rest.dto.LeaveRequest;
import me.prexorjustin.prexorcloud.modules.stats.rest.dto.PlayerDetailResponse;
import me.prexorjustin.prexorcloud.modules.stats.rest.dto.RebuildResponse;
import me.prexorjustin.prexorcloud.modules.stats.rest.dto.TopGroupsResponse;
import me.prexorjustin.prexorcloud.modules.stats.rest.dto.TopPlayersResponse;
import me.prexorjustin.prexorcloud.modules.stats.service.JourneyEnricher;
import me.prexorjustin.prexorcloud.modules.stats.service.LeaderboardService;
import me.prexorjustin.prexorcloud.modules.stats.service.SessionAggregator;

public final class StatsRoutes {

    private final StatsRepository repo;
    private final SessionAggregator aggregator;
    private final LeaderboardService leaderboard;
    private final JourneyEnricher journey;
    private final PrometheusExporter prometheus;
    private final StatsConfig config;
    private final Clock clock;

    public StatsRoutes(
            StatsRepository repo,
            SessionAggregator aggregator,
            LeaderboardService leaderboard,
            JourneyEnricher journey,
            PrometheusExporter prometheus,
            StatsConfig config,
            Clock clock) {
        this.repo = repo;
        this.aggregator = aggregator;
        this.leaderboard = leaderboard;
        this.journey = journey;
        this.prometheus = prometheus;
        this.config = config;
        this.clock = clock;
    }

    public void register(RouteRegistrar routes) {
        routes.get("/players/top", (req, res) -> {
            int limit = parseLimit(req.queryParam("limit").orElse(null), config.leaderboardSize());
            if (limit < 0) {
                res.status(400).json(Map.of("error", "invalid limit"));
                return;
            }
            var top = leaderboard.topPlayers(limit);
            res.json(new TopPlayersResponse(top.size(), top));
        });

        routes.get("/groups/top", (req, res) -> {
            int limit = parseLimit(req.queryParam("limit").orElse(null), config.leaderboardSize());
            if (limit < 0) {
                res.status(400).json(Map.of("error", "invalid limit"));
                return;
            }
            var top = leaderboard.topGroups(limit);
            res.json(new TopGroupsResponse(top.size(), top));
        });

        routes.get("/players/{uuid}", (req, res) -> {
            UUID playerId;
            try {
                playerId = UUID.fromString(req.pathParam("uuid"));
            } catch (IllegalArgumentException _) {
                res.status(400).json(Map.of("error", "invalid uuid"));
                return;
            }
            var stat = repo.playerStat(playerId);
            var sessions = repo.recentSessionsForPlayer(playerId, 10);
            if (stat.isEmpty() && sessions.isEmpty()) {
                res.status(404).json(Map.of("error", "player not found"));
                return;
            }
            List<PlayerJourneyEntry> recentJourney =
                    journey.recent(playerId, 25).orElse(List.of());
            res.json(new PlayerDetailResponse(stat.orElse(null), sessions, recentJourney));
        });

        routes.post("/sessions/join", (req, res) -> {
            JoinRequest body;
            try {
                body = req.bodyAs(JoinRequest.class);
            } catch (Exception _) {
                res.status(400).json(Map.of("error", "invalid json body"));
                return;
            }
            if (body == null || body.playerId() == null || body.sessionId() == null || body.joinAt() == null) {
                res.status(400).json(Map.of("error", "missing required field: playerId, sessionId, joinAt"));
                return;
            }
            aggregator.onJoin(
                    body.playerId(),
                    body.playerName(),
                    body.sessionId(),
                    body.group(),
                    body.instanceId(),
                    body.joinAt());
            res.status(202).json(Map.of("ok", true));
        });

        routes.post("/sessions/leave", (req, res) -> {
            LeaveRequest body;
            try {
                body = req.bodyAs(LeaveRequest.class);
            } catch (Exception _) {
                res.status(400).json(Map.of("error", "invalid json body"));
                return;
            }
            if (body == null || body.sessionId() == null || body.quitAt() == null) {
                res.status(400).json(Map.of("error", "missing required field: sessionId, quitAt"));
                return;
            }
            if (body.durationMs() != null && body.durationMs() < 0) {
                res.status(400).json(Map.of("error", "durationMs must be >= 0"));
                return;
            }
            var outcome = aggregator.onLeave(body.sessionId(), body.quitAt(), body.durationMs());
            if (outcome == SessionAggregator.CloseOutcome.NOT_FOUND) {
                res.status(404).json(Map.of("error", "session not found"));
                return;
            }
            res.status(202).json(Map.of("ok", true));
        });

        routes.post("/aggregates/rebuild", (req, res) -> {
            var result = aggregator.rebuild(clock.instant());
            res.json(new RebuildResponse(result.players(), result.groups(), result.sessions()));
        });

        routes.get("/metrics", (req, res) -> {
            if (!config.prometheusEnabled()) {
                res.status(404).json(Map.of("error", "metrics disabled"));
                return;
            }
            res.header("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            res.text(prometheus.render());
        });

        routes.get("/config", (req, res) -> res.json(config));
    }

    private static int parseLimit(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int v = Integer.parseInt(raw);
            return v <= 0 ? fallback : Math.min(v, fallback * 4);
        } catch (NumberFormatException _) {
            return -1;
        }
    }
}
