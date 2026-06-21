package me.prexorjustin.prexorcloud.controller.state;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;

/**
 * MongoDB-backed {@link WorkloadTokenStore}. Plugin tokens live in
 * {@code plugin_tokens} keyed by the opaque token, the per-instance replay
 * high-water mark in {@code workload_sequences}; both carry an absolute
 * {@code expiresAt} reaped by a TTL index. Replaces the Redis projection's
 * token + Lua-sequence handling with the same write-through-then-read-through
 * contract, so the in-memory {@link WorkloadIdentityRegistry} stays the hot path
 * and Mongo is consulted only on a cache miss / takeover.
 */
public final class MongoWorkloadTokenStore implements WorkloadTokenStore {

    private static final String TOKENS = "plugin_tokens";
    private static final String SEQUENCES = "workload_sequences";
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);
    private static final FindOneAndUpdateOptions ACCEPT_OPTS =
            new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.BEFORE);

    private final MongoCollection<Document> tokens;
    private final MongoCollection<Document> sequences;

    public MongoWorkloadTokenStore(MongoDatabase db) {
        Objects.requireNonNull(db, "db");
        this.tokens = db.getCollection(TOKENS);
        this.sequences = db.getCollection(SEQUENCES);
        this.tokens.createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("plugin_tokens_ttl"));
        this.sequences.createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("workload_sequences_ttl"));
    }

    @Override
    public void saveToken(String token, WorkloadIdentityRegistry.PluginTokenEntry entry) {
        if (token == null || entry == null) return;
        tokens.replaceOne(
                Filters.eq("_id", token),
                new Document("_id", token)
                        .append("tokenId", entry.tokenId())
                        .append("instanceId", entry.instanceId())
                        .append("issuedAt", Date.from(entry.issuedAt()))
                        .append("expiresAt", Date.from(entry.expiresAt())),
                UPSERT);
    }

    @Override
    public void removeToken(String token) {
        if (token == null) return;
        tokens.deleteOne(Filters.eq("_id", token));
    }

    @Override
    public Optional<WorkloadIdentityRegistry.PluginTokenEntry> loadToken(String token) {
        if (token == null) return Optional.empty();
        Document doc = tokens.find(Filters.eq("_id", token)).first();
        return Optional.ofNullable(doc).map(MongoWorkloadTokenStore::toEntry);
    }

    @Override
    public Map<String, WorkloadIdentityRegistry.PluginTokenEntry> loadAllTokens() {
        Map<String, WorkloadIdentityRegistry.PluginTokenEntry> out = new HashMap<>();
        for (Document doc : tokens.find()) {
            String token = doc.getString("_id");
            WorkloadIdentityRegistry.PluginTokenEntry entry = toEntry(doc);
            if (token != null && entry != null) out.put(token, entry);
        }
        return out;
    }

    @Override
    public boolean acceptSequence(String instanceId, long sequence, Duration ttl) {
        long ttlMs = Math.max(1L, ttl == null ? 0L : ttl.toMillis());
        // Atomic accept-if-greater: keep max(stored, incoming), refresh expiry, and report
        // whether the incoming sequence advanced the high-water mark (the pre-image tells us).
        List<Document> pipeline = List.of(new Document(
                "$set",
                new Document()
                        .append(
                                "sequence",
                                new Document(
                                        "$max",
                                        List.of(new Document("$ifNull", List.of("$sequence", sequence)), sequence)))
                        .append("expiresAt", new Document("$add", List.of("$$NOW", ttlMs)))));
        Document before = sequences.findOneAndUpdate(Filters.eq("_id", instanceId), pipeline, ACCEPT_OPTS);
        if (before == null) return true; // first sequence for this instance
        Number prior = before.get("sequence", Number.class);
        return prior == null || sequence > prior.longValue();
    }

    @Override
    public void clearSequence(String instanceId) {
        if (instanceId == null) return;
        sequences.deleteOne(Filters.eq("_id", instanceId));
    }

    private static WorkloadIdentityRegistry.PluginTokenEntry toEntry(Document doc) {
        Date issuedAt = doc.getDate("issuedAt");
        Date expiresAt = doc.getDate("expiresAt");
        if (expiresAt == null) return null;
        return new WorkloadIdentityRegistry.PluginTokenEntry(
                doc.getString("tokenId"),
                doc.getString("instanceId"),
                issuedAt == null ? Instant.EPOCH : issuedAt.toInstant(),
                expiresAt.toInstant());
    }
}
