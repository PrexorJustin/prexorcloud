package me.prexorjustin.prexorcloud.modules.example.data;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;
import me.prexorjustin.prexorcloud.api.module.data.Update;

/**
 * All persistence for the playtime tracker lives here.
 *
 * <p>STEP 6 — A repository class keeps data access in one place and off the
 * route handlers, so routes stay dumb and the ModuleDataStore API is easy to
 * grep for. The store auto-prefixes your collection names with {@code
 * "mod_example-playtime_"} — you write {@code "sessions"}, it stores in
 * {@code mod_example-playtime_sessions}.
 */
public final class PlaytimeRepository {

    public static final String SESSIONS = "sessions";
    public static final String TOTALS = "totals";

    private final ModuleDataStore store;

    public PlaytimeRepository(ModuleDataStore store) {
        this.store = store;
        ensureSchema();
    }

    /**
     * STEP 6a — Collection + index bootstrap. Safe to call on every enable:
     * {@code ensureCollection} is a no-op if the collection already exists, and
     * {@code createIndex} is idempotent on the underlying driver.
     */
    private void ensureSchema() {
        store.ensureCollection(SESSIONS);
        store.ensureCollection(TOTALS);
        store.createIndex(SESSIONS, IndexSpec.asc("playerId"));
        store.createIndex(SESSIONS, IndexSpec.asc("sessionId").asUnique());
        store.createIndex(SESSIONS, IndexSpec.desc("joinAt"));
        store.createIndex(TOTALS, IndexSpec.asc("playerId").asUnique());
        store.createIndex(TOTALS, IndexSpec.desc("totalMs"));
    }

    // ── Session writes ──────────────────────────────────────────────────────

    /** STEP 6b — Write a fresh session on PLAYTIME:SESSION_START. */
    public void openSession(Session session) {
        store.insertOne(SESSIONS, session);
    }

    /**
     * STEP 6c — Close an open session on PLAYTIME:SESSION_END. Upsert keeps the
     * endpoint idempotent: if the session wasn't written on start (e.g. plugin
     * restarted mid-session), the end event still creates a finalised record.
     */
    public void closeSession(UUID sessionId, Instant quitAt, long durationMs) {
        store.updateOne(
                SESSIONS,
                Query.where("sessionId").eq(sessionId.toString()),
                Update.set("quitAt", quitAt).andSet("durationMs", durationMs));
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** STEP 6d — Recent sessions for a player (newest first). */
    public List<Session> recentSessions(UUID playerId, int limit) {
        return store.find(
                SESSIONS, Query.where("playerId").eq(playerId.toString()), Sort.desc("joinAt"), limit, Session.class);
    }

    public Optional<TopEntry> totalFor(UUID playerId) {
        return store.findOne(TOTALS, Query.where("playerId").eq(playerId.toString()), TopEntry.class);
    }

    /** STEP 6e — Top-N leaderboard read from the cached totals collection. */
    public List<TopEntry> top(int limit) {
        return store.find(TOTALS, Query.all(), Sort.desc("totalMs"), limit, TopEntry.class);
    }

    /**
     * STEP 6f — Rebuild the {@code totals} cache from the raw {@code sessions}
     * collection. Runs inside a transaction so the dashboard never sees a
     * half-rebuilt leaderboard.
     */
    public int rebuildTotals() {
        Map<UUID, long[]> acc = new HashMap<>();
        Map<UUID, Instant> lastSeen = new HashMap<>();
        List<Session> all = store.find(SESSIONS, Query.all(), Sort.none(), Integer.MAX_VALUE, Session.class);
        for (Session s : all) {
            long[] agg = acc.computeIfAbsent(s.playerId(), k -> new long[] {0, 0});
            agg[0] += s.durationMs();
            agg[1]++;
            Instant seen = s.quitAt() != null ? s.quitAt() : s.joinAt();
            lastSeen.merge(s.playerId(), seen, (a, b) -> a.isAfter(b) ? a : b);
        }

        int[] wrote = new int[] {0};
        store.withTransaction(tx -> {
            tx.deleteMany(TOTALS, Query.all());
            var entries = acc.entrySet().stream()
                    .map(e ->
                            new TopEntry(e.getKey(), e.getValue()[0], (int) e.getValue()[1], lastSeen.get(e.getKey())))
                    .sorted(Comparator.comparingLong(TopEntry::totalMs).reversed())
                    .toList();
            tx.insertMany(TOTALS, entries);
            wrote[0] = entries.size();
        });
        return wrote[0];
    }

    // ── Retention ───────────────────────────────────────────────────────────

    /** STEP 6g — Delete sessions older than the retention window. */
    public int deleteOlderThan(Instant cutoff) {
        return store.deleteMany(SESSIONS, Query.where("joinAt").lt(cutoff));
    }
}
