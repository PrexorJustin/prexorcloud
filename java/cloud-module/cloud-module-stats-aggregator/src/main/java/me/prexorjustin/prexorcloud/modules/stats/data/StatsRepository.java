package me.prexorjustin.prexorcloud.modules.stats.data;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;
import me.prexorjustin.prexorcloud.api.module.data.Update;

public final class StatsRepository {

    public static final String SESSIONS = "sessions";
    public static final String PLAYERS = "players";
    public static final String GROUPS = "groups";

    private final ModuleDataStore store;

    public StatsRepository(ModuleDataStore store) {
        this.store = store;
        ensureSchema();
    }

    private void ensureSchema() {
        store.ensureCollection(SESSIONS);
        store.ensureCollection(PLAYERS);
        store.ensureCollection(GROUPS);
        store.createIndex(SESSIONS, IndexSpec.asc("sessionId").asUnique());
        store.createIndex(SESSIONS, IndexSpec.asc("playerId"));
        store.createIndex(SESSIONS, IndexSpec.asc("group"));
        store.createIndex(SESSIONS, IndexSpec.desc("joinAt"));
        store.createIndex(PLAYERS, IndexSpec.asc("playerId").asUnique());
        store.createIndex(PLAYERS, IndexSpec.desc("totalMs"));
        store.createIndex(GROUPS, IndexSpec.asc("group").asUnique());
        store.createIndex(GROUPS, IndexSpec.desc("totalMs"));
    }

    // ── Session writes ──────────────────────────────────────────────────────

    public void openSession(SessionRecord session) {
        store.upsertOne(
                SESSIONS,
                Query.where("sessionId").eq(session.sessionId().toString()),
                Update.setOnInsert("playerId", session.playerId().toString())
                        .andSetOnInsert("sessionId", session.sessionId().toString())
                        .andSetOnInsert("playerName", session.playerName())
                        .andSetOnInsert("group", session.group())
                        .andSetOnInsert("instanceId", session.instanceId())
                        .andSetOnInsert("joinAt", session.joinAt())
                        .andSetOnInsert("quitAt", null)
                        .andSetOnInsert("durationMs", 0L));
    }

    public void closeSession(UUID sessionId, Instant quitAt, long durationMs) {
        store.updateOne(
                SESSIONS,
                Query.where("sessionId").eq(sessionId.toString()),
                Update.set("quitAt", quitAt).andSet("durationMs", durationMs));
    }

    public Optional<SessionRecord> findSession(UUID sessionId) {
        return store.findOne(SESSIONS, Query.where("sessionId").eq(sessionId.toString()), SessionRecord.class);
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public Optional<PlayerStat> playerStat(UUID playerId) {
        return store.findOne(PLAYERS, Query.where("playerId").eq(playerId.toString()), PlayerStat.class);
    }

    public List<PlayerStat> topPlayers(int limit) {
        return store.find(PLAYERS, Query.all(), Sort.desc("totalMs"), limit, PlayerStat.class);
    }

    public List<GroupStat> topGroups(int limit) {
        return store.find(GROUPS, Query.all(), Sort.desc("totalMs"), limit, GroupStat.class);
    }

    public List<SessionRecord> recentSessionsForPlayer(UUID playerId, int limit) {
        return store.find(
                SESSIONS,
                Query.where("playerId").eq(playerId.toString()),
                Sort.desc("joinAt"),
                limit,
                SessionRecord.class);
    }

    public long sessionCount() {
        return store.count(SESSIONS, Query.all());
    }

    public long playerCount() {
        return store.count(PLAYERS, Query.all());
    }

    // ── Aggregation ─────────────────────────────────────────────────────────

    /** Rebuild player + group totals from the raw sessions collection. */
    public RebuildResult rebuildAggregates(Instant now) {
        List<SessionRecord> all =
                store.find(SESSIONS, Query.all(), Sort.none(), Integer.MAX_VALUE, SessionRecord.class);

        Map<UUID, PlayerAccumulator> players = new HashMap<>();
        Map<String, GroupAccumulator> groups = new HashMap<>();
        for (SessionRecord s : all) {
            if (s.durationMs() <= 0) {
                // open session — count toward session count + last-seen but not totalMs
            }
            players.computeIfAbsent(s.playerId(), k -> new PlayerAccumulator(s.playerName()))
                    .add(s);
            if (s.group() != null && !s.group().isBlank()) {
                groups.computeIfAbsent(s.group(), k -> new GroupAccumulator()).add(s);
            }
        }

        int[] writtenPlayers = {0};
        int[] writtenGroups = {0};
        store.withTransaction(tx -> {
            tx.deleteMany(PLAYERS, Query.all());
            tx.deleteMany(GROUPS, Query.all());
            List<PlayerStat> playerRows = players.entrySet().stream()
                    .map(e -> e.getValue().toRow(e.getKey()))
                    .toList();
            List<GroupStat> groupRows = groups.entrySet().stream()
                    .map(e -> e.getValue().toRow(e.getKey(), now))
                    .toList();
            if (!playerRows.isEmpty()) {
                tx.insertMany(PLAYERS, playerRows);
                writtenPlayers[0] = playerRows.size();
            }
            if (!groupRows.isEmpty()) {
                tx.insertMany(GROUPS, groupRows);
                writtenGroups[0] = groupRows.size();
            }
        });
        return new RebuildResult(writtenPlayers[0], writtenGroups[0], all.size());
    }

    public int deleteSessionsOlderThan(Instant cutoff) {
        return store.deleteMany(SESSIONS, Query.where("joinAt").lt(cutoff));
    }

    public record RebuildResult(int players, int groups, int sessions) {}

    private static final class PlayerAccumulator {
        private String name;
        private long totalMs;
        private int sessionCount;
        private Instant firstSeen;
        private Instant lastSeen;

        PlayerAccumulator(String name) {
            this.name = name == null ? "" : name;
        }

        void add(SessionRecord s) {
            if (s.playerName() != null && !s.playerName().isBlank()) {
                name = s.playerName();
            }
            totalMs += Math.max(0L, s.durationMs());
            sessionCount++;
            Instant seenStart = s.joinAt();
            Instant seenEnd = s.quitAt() != null ? s.quitAt() : s.joinAt();
            firstSeen =
                    (firstSeen == null || (seenStart != null && seenStart.isBefore(firstSeen))) ? seenStart : firstSeen;
            lastSeen = (lastSeen == null || (seenEnd != null && seenEnd.isAfter(lastSeen))) ? seenEnd : lastSeen;
        }

        PlayerStat toRow(UUID playerId) {
            return new PlayerStat(playerId, name, totalMs, sessionCount, firstSeen, lastSeen);
        }
    }

    private static final class GroupAccumulator {
        private long totalMs;
        private int sessionCount;
        private final Set<UUID> players = new HashSet<>();

        void add(SessionRecord s) {
            totalMs += Math.max(0L, s.durationMs());
            sessionCount++;
            players.add(s.playerId());
        }

        GroupStat toRow(String group, Instant now) {
            return new GroupStat(group, totalMs, sessionCount, players.size(), now);
        }
    }
}
