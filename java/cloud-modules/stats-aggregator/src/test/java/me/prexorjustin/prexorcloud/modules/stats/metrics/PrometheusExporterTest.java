package me.prexorjustin.prexorcloud.modules.stats.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import me.prexorjustin.prexorcloud.modules.stats.data.GroupStat;
import me.prexorjustin.prexorcloud.modules.stats.data.PlayerStat;
import me.prexorjustin.prexorcloud.modules.stats.data.StatsRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PrometheusExporter")
class PrometheusExporterTest {

    @Mock
    StatsRepository repo;

    @Test
    @DisplayName("Renders TYPE/HELP headers and metric lines for known series")
    void rendersMetricLines() {
        UUID p1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant now = Instant.parse("2026-04-13T12:00:00Z");
        when(repo.sessionCount()).thenReturn(42L);
        when(repo.playerCount()).thenReturn(7L);
        when(repo.topPlayers(5)).thenReturn(List.of(new PlayerStat(p1, "alice", 1_000L, 3, now, now)));
        when(repo.topGroups(5)).thenReturn(List.of(new GroupStat("lobby", 5_000L, 12, 4, now)));

        String out = new PrometheusExporter(repo, 5).render();

        assertTrue(out.contains("# TYPE stats_aggregator_sessions_total counter"));
        assertTrue(out.contains("stats_aggregator_sessions_total 42"));
        assertTrue(out.contains("stats_aggregator_players_total 7"));
        assertTrue(
                out.contains(
                        "stats_aggregator_player_playtime_ms{player_id=\"00000000-0000-0000-0000-000000000001\",player_name=\"alice\"} 1000"));
        assertTrue(out.contains("stats_aggregator_group_playtime_ms{group=\"lobby\"} 5000"));
        assertTrue(out.contains("stats_aggregator_group_unique_players{group=\"lobby\"} 4"));
    }

    @Test
    @DisplayName("Escapes label values for double-quote and backslash")
    void escapesLabels() {
        UUID p1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(repo.sessionCount()).thenReturn(0L);
        when(repo.playerCount()).thenReturn(0L);
        when(repo.topPlayers(5)).thenReturn(List.of(new PlayerStat(p1, "name\"with\\backslash", 1L, 1, null, null)));
        when(repo.topGroups(5)).thenReturn(List.of());

        String out = new PrometheusExporter(repo, 5).render();

        assertTrue(out.contains("player_name=\"name\\\"with\\\\backslash\""));
    }
}
