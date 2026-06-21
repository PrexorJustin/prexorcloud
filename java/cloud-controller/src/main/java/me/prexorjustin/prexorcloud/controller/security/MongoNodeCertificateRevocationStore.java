package me.prexorjustin.prexorcloud.controller.security;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

/**
 * MongoDB-backed {@link NodeCertificateRevocationStore}. Mirrors the dual-key
 * Redis store: a revocation is written under both a {@code serial:<hex>} and a
 * {@code cn:<cn>} document so a certificate can be matched by either identifier
 * during a TLS handshake. A TTL index on {@code expiresAt} bounds the collection
 * to certificates that have not yet expired naturally.
 *
 * <p>Durable so revocations survive a controller restart / leadership change —
 * the gRPC trust manager on whichever controller a daemon lands on after a
 * failover must still reject a revoked node. {@link #isRevoked} is two indexed
 * {@code _id} point lookups and compares {@code expiresAt} against now rather
 * than trusting the TTL sweep.
 */
public final class MongoNodeCertificateRevocationStore implements NodeCertificateRevocationStore {

    private static final String COLLECTION = "node_cert_revocations";
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);
    private static final Duration DEFAULT_TTL = Duration.ofDays(365);
    private static final String SERIAL_PREFIX = "serial:";
    private static final String CN_PREFIX = "cn:";

    private final MongoCollection<Document> collection;

    public MongoNodeCertificateRevocationStore(MongoDatabase db) {
        this.collection = Objects.requireNonNull(db, "db").getCollection(COLLECTION);
        this.collection.createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("node_cert_revocations_ttl"));
    }

    @Override
    public void revoke(BigInteger serial, String subjectCn, Duration ttl) {
        Date expiresAt =
                Date.from(Instant.now().plus(ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl));
        String cn = safeCn(subjectCn);
        if (serial != null) {
            String id = SERIAL_PREFIX + serial.toString(16);
            collection.replaceOne(
                    Filters.eq("_id", id),
                    new Document("_id", id).append("cn", cn).append("expiresAt", expiresAt),
                    UPSERT);
        }
        if (!cn.isEmpty()) {
            String id = CN_PREFIX + cn;
            String marker = serial == null ? "" : serial.toString(16);
            collection.replaceOne(
                    Filters.eq("_id", id),
                    new Document("_id", id).append("serial", marker).append("expiresAt", expiresAt),
                    UPSERT);
        }
    }

    @Override
    public void unrevoke(BigInteger serial, String subjectCn) {
        if (serial != null) {
            collection.deleteOne(Filters.eq("_id", SERIAL_PREFIX + serial.toString(16)));
        }
        String cn = safeCn(subjectCn);
        if (!cn.isEmpty()) {
            collection.deleteOne(Filters.eq("_id", CN_PREFIX + cn));
        }
    }

    @Override
    public boolean isRevoked(BigInteger serial, String subjectCn) {
        if (serial != null && present(SERIAL_PREFIX + serial.toString(16))) {
            return true;
        }
        String cn = safeCn(subjectCn);
        return !cn.isEmpty() && present(CN_PREFIX + cn);
    }

    @Override
    public Set<String> revokedSubjectCns() {
        Set<String> result = new HashSet<>();
        Date now = Date.from(Instant.now());
        var filter = Filters.and(
                Filters.regex("_id", Pattern.compile("^" + Pattern.quote(CN_PREFIX))), Filters.gt("expiresAt", now));
        for (Document doc : collection.find(filter)) {
            String id = doc.getString("_id");
            if (id != null && id.startsWith(CN_PREFIX)) {
                result.add(id.substring(CN_PREFIX.length()));
            }
        }
        return Set.copyOf(result);
    }

    private boolean present(String id) {
        Document doc = collection.find(Filters.eq("_id", id)).first();
        if (doc == null) return false;
        Date expiresAt = doc.getDate("expiresAt");
        return expiresAt != null && expiresAt.toInstant().isAfter(Instant.now());
    }

    private static String safeCn(String cn) {
        return cn == null ? "" : cn.trim();
    }
}
