package me.prexorjustin.prexorcloud.modules.stats.metrics;

import java.util.List;

import me.prexorjustin.prexorcloud.modules.stats.data.GroupStat;
import me.prexorjustin.prexorcloud.modules.stats.data.PlayerStat;
import me.prexorjustin.prexorcloud.modules.stats.data.StatsRepository;

/**
 * Renders the module's aggregates in Prometheus text exposition format.
 *
 * <p>Scope: this exporter intentionally does not pull in Micrometer — modules
 * compile against cloud-api only and the controller's MetricsCollector is not
 * exposed as a capability. Hand-rendering the well-defined v0.0.4 text format
 * keeps the dependency surface zero and the output trivially diffable.
 */
public final class PrometheusExporter {

    private final StatsRepository repo;
    private final int leaderboardSize;

    public PrometheusExporter(StatsRepository repo, int leaderboardSize) {
        this.repo = repo;
        this.leaderboardSize = Math.max(1, leaderboardSize);
    }

    public String render() {
        StringBuilder out = new StringBuilder(2048);
        out.append("# HELP stats_aggregator_sessions_total Total recorded sessions across all players.\n");
        out.append("# TYPE stats_aggregator_sessions_total counter\n");
        out.append("stats_aggregator_sessions_total ")
                .append(repo.sessionCount())
                .append('\n');

        out.append("# HELP stats_aggregator_players_total Distinct players observed.\n");
        out.append("# TYPE stats_aggregator_players_total gauge\n");
        out.append("stats_aggregator_players_total ").append(repo.playerCount()).append('\n');

        List<PlayerStat> topPlayers = repo.topPlayers(leaderboardSize);
        out.append("# HELP stats_aggregator_player_playtime_ms Per-player total playtime in milliseconds.\n");
        out.append("# TYPE stats_aggregator_player_playtime_ms gauge\n");
        for (PlayerStat p : topPlayers) {
            out.append("stats_aggregator_player_playtime_ms{player_id=\"")
                    .append(escape(p.playerId().toString()))
                    .append("\",player_name=\"")
                    .append(escape(p.playerName()))
                    .append("\"} ")
                    .append(p.totalMs())
                    .append('\n');
        }

        List<GroupStat> topGroups = repo.topGroups(leaderboardSize);
        out.append("# HELP stats_aggregator_group_playtime_ms Per-group total playtime in milliseconds.\n");
        out.append("# TYPE stats_aggregator_group_playtime_ms gauge\n");
        for (GroupStat g : topGroups) {
            out.append("stats_aggregator_group_playtime_ms{group=\"")
                    .append(escape(g.group()))
                    .append("\"} ")
                    .append(g.totalMs())
                    .append('\n');
        }

        out.append("# HELP stats_aggregator_group_unique_players Per-group distinct players observed.\n");
        out.append("# TYPE stats_aggregator_group_unique_players gauge\n");
        for (GroupStat g : topGroups) {
            out.append("stats_aggregator_group_unique_players{group=\"")
                    .append(escape(g.group()))
                    .append("\"} ")
                    .append(g.uniquePlayers())
                    .append('\n');
        }
        return out.toString();
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
