package me.prexorjustin.prexorcloud.controller.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

/**
 * MongoDB-backed {@link JwtRevocationStore}. One document per revoked {@code jti}
 * carries an absolute {@code expiresAt} date; a TTL index reaps the document once
 * the token would have expired anyway, so the collection stays bounded.
 *
 * <p>Survives controller restart and leadership change — the single property the
 * in-memory development store lacks, and the reason JWT revocation is a durable
 * store rather than leader memory: a revoked token must stay revoked across a
 * failover. {@link #isRevoked(String)} compares {@code expiresAt} against the
 * current time instead of trusting Mongo's ~60s TTL sweep, so a marker that has
 * naturally expired is never reported as still-revoked.
 */
public final class MongoJwtRevocationStore implements JwtRevocationStore {

    private static final String COLLECTION = "jwt_revocations";
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private final MongoCollection<Document> collection;
    private volatile MetricsCollector metricsCollector;

    public MongoJwtRevocationStore(MongoDatabase db) {
        this.collection = Objects.requireNonNull(db, "db").getCollection(COLLECTION);
        this.collection.createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("jwt_revocations_ttl"));
    }

    public void attachMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    @Override
    public void revoke(String jti, Duration ttl) {
        if (jti == null || jti.isBlank()) return;
        Instant expiresAt = Instant.now().plus(sanitize(ttl));
        collection.replaceOne(
                Filters.eq("_id", jti), new Document("_id", jti).append("expiresAt", Date.from(expiresAt)), UPSERT);
        MetricsCollector mc = metricsCollector;
        if (mc != null) mc.recordJwtRevocation();
    }

    @Override
    public boolean isRevoked(String jti) {
        if (jti == null) return false;
        Document doc = collection.find(Filters.eq("_id", jti)).first();
        if (doc == null) return false;
        Date expiresAt = doc.getDate("expiresAt");
        return expiresAt != null && expiresAt.toInstant().isAfter(Instant.now());
    }

    private static Duration sanitize(Duration ttl) {
        return ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    }
}
