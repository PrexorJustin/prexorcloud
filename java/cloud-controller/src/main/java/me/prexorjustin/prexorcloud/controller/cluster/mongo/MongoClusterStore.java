package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;

/**
 * Mongo-backed persistence for the cluster-membership state types (Phase 4 of the single-writer
 * rewrite): cluster identity, the member roster, join tokens, and the cluster CA files needed to
 * admit a joiner. This is the durable home that lets cluster join become "register a document in
 * Mongo" instead of completing an embedded-Raft group join — the mTLS join bootstrap
 * (TLSV1_ALERT_CERTIFICATE_REQUIRED) that blocked multi-controller standup is exactly the machinery
 * this retires.
 *
 * <p>It mirrors the read/write surface of the Raft {@code ClusterControlPlane} for the migratable
 * state so a later slice can dual-write (Raft primary, this the shadow) and then cut the read path
 * over behind a flag. Like {@link me.prexorjustin.prexorcloud.controller.cluster.MongoLeaderElector}
 * in Phase 1, it lands as a tested primitive with <em>nothing wired to it yet</em> — zero behavior
 * change until a deliberate, fleet-gated cutover.
 *
 * <p>Cluster identity lives in {@code cluster_identity} (a distinct collection from the legacy v1.0
 * {@code cluster_meta} doc, which the v1.0→v1.1 migration still reads and then drops — reusing it
 * would risk that drop deleting live identity).
 *
 * <p>Guarded-write semantics (per the rewrite plan's consistency table):
 * <ul>
 *   <li><b>identity</b> — compare-and-set on {@code clusterId} (anti-fork: two controllers must
 *       never disagree on which cluster they belong to);
 *   <li><b>members</b> — upsert by nodeId (address self-heal);
 *   <li><b>join tokens</b> — single-use redeem via guarded {@code findOneAndUpdate}
 *       (not-redeemed, not-revoked, not-expired);
 *   <li><b>cluster files</b> (CA cert/key) — last-write-wins upsert.
 * </ul>
 */
public final class MongoClusterStore {

    public static final String IDENTITY_COLLECTION = "cluster_identity";
    public static final String MEMBERS_COLLECTION = "cluster_members";
    public static final String JOIN_TOKENS_COLLECTION = "cluster_join_tokens";
    public static final String FILES_COLLECTION = "cluster_files";

    /** Singleton {@code _id} for the cluster-identity document. */
    static final String IDENTITY_ID = "cluster";

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final MongoCollection<Document> identity;
    private final MongoCollection<Document> members;
    private final MongoCollection<Document> joinTokens;
    private final MongoCollection<Document> files;

    public MongoClusterStore(MongoDatabase db) {
        this.identity = majority(db, IDENTITY_COLLECTION);
        this.members = majority(db, MEMBERS_COLLECTION);
        this.joinTokens = majority(db, JOIN_TOKENS_COLLECTION);
        this.files = majority(db, FILES_COLLECTION);
    }

    private static MongoCollection<Document> majority(MongoDatabase db, String name) {
        // Cluster membership is small + correctness-critical (anti-fork CAS, single-use redeem), so
        // mirror the lease: majority write + read survives a Mongo primary failover.
        return db.getCollection(name)
                .withWriteConcern(WriteConcern.MAJORITY)
                .withReadConcern(ReadConcern.MAJORITY);
    }

    // --- cluster identity (anti-fork CAS) -------------------------------------------------------

    public Optional<ClusterMeta> getClusterMeta() {
        return Optional.ofNullable(identity.find(Filters.eq("_id", IDENTITY_ID)).first())
                .map(MongoClusterStore::toClusterMeta);
    }

    /**
     * Persist cluster identity. First write wins on {@code clusterId}; a later write with the SAME
     * clusterId idempotently refreshes the other fields (e.g. the rotated seed secret). A write with
     * a DIFFERENT clusterId is rejected with {@link ClusterForkException}: the document {@code _id}
     * is the singleton {@code "cluster"}, so an upsert filtered on the new clusterId can only match
     * the existing identity or attempt to insert a second {@code _id} (a duplicate-key error =
     * the fork signal).
     */
    public void putClusterMeta(ClusterMeta meta) {
        Document full = clusterMetaDoc(meta);
        Document set = new Document(full);
        set.remove("_id");
        set.remove("clusterId");
        try {
            identity.updateOne(
                    Filters.and(Filters.eq("_id", IDENTITY_ID), Filters.eq("clusterId", meta.clusterId())),
                    new Document("$set", set),
                    new UpdateOptions().upsert(true));
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                String existing = getClusterMeta().map(ClusterMeta::clusterId).orElse("<unknown>");
                throw new ClusterForkException(existing, meta.clusterId());
            }
            throw e;
        }
    }

    // --- members (upsert by nodeId) -------------------------------------------------------------

    public List<Member> listMembers() {
        List<Member> out = new ArrayList<>();
        for (Document doc : members.find().sort(Sorts.ascending("_id"))) {
            out.add(toMember(doc));
        }
        return out;
    }

    public Optional<Member> getMember(String nodeId) {
        return Optional.ofNullable(members.find(Filters.eq("_id", nodeId)).first())
                .map(MongoClusterStore::toMember);
    }

    /** Register or self-heal a member (address may change across reconnects). */
    public void putMember(Member member) {
        members.replaceOne(Filters.eq("_id", member.nodeId()), memberDoc(member), UPSERT);
    }

    public boolean removeMember(String nodeId) {
        return members.deleteOne(Filters.eq("_id", nodeId)).getDeletedCount() > 0;
    }

    // --- join tokens (single-use redeem) --------------------------------------------------------

    public void putJoinToken(JoinToken token) {
        joinTokens.replaceOne(Filters.eq("_id", token.jti()), joinTokenDoc(token), UPSERT);
    }

    public Optional<JoinToken> getJoinToken(String jti) {
        return Optional.ofNullable(joinTokens.find(Filters.eq("_id", jti)).first())
                .map(MongoClusterStore::toJoinToken);
    }

    public List<JoinToken> listJoinTokens() {
        List<JoinToken> out = new ArrayList<>();
        for (Document doc : joinTokens.find().sort(Sorts.ascending("_id"))) {
            out.add(toJoinToken(doc));
        }
        return out;
    }

    /**
     * Atomically redeem a token exactly once. Succeeds iff the token exists, has not been redeemed,
     * is not revoked, and has not expired as of {@code redeemedAt}. Concurrent redeems race on the
     * single conditional {@code findOneAndUpdate}: exactly one observes the document and flips it.
     *
     * @return {@code true} iff this call performed the redemption.
     */
    public boolean redeemJoinToken(String jti, Instant redeemedAt, String redeemedFrom, String redeemedAs) {
        Bson guard = Filters.and(
                Filters.eq("_id", jti),
                Filters.eq("redeemedAt", null),
                Filters.ne("revoked", true),
                Filters.gt("expiresAt", Date.from(redeemedAt)));
        Bson update = Updates.combine(
                Updates.set("redeemedAt", Date.from(redeemedAt)),
                Updates.set("redeemedFrom", redeemedFrom),
                Updates.set("redeemedAs", redeemedAs));
        return joinTokens.findOneAndUpdate(
                        guard, update, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER))
                != null;
    }

    /** Revoke a token so it can no longer be redeemed. Idempotent (returns false if already revoked). */
    public boolean revokeJoinToken(String jti, String revokedBy, Instant revokedAt) {
        Bson guard = Filters.and(Filters.eq("_id", jti), Filters.ne("revoked", true));
        Bson update = Updates.combine(
                Updates.set("revoked", true),
                Updates.set("revokedBy", revokedBy),
                Updates.set("revokedAt", Date.from(revokedAt)));
        return joinTokens.findOneAndUpdate(
                        guard, update, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER))
                != null;
    }

    // --- cluster files (CA cert/key, last-write-wins) -------------------------------------------

    public Optional<ClusterFile> getClusterFile(String key) {
        return Optional.ofNullable(files.find(Filters.eq("_id", key)).first())
                .map(MongoClusterStore::toClusterFile);
    }

    public void putClusterFile(ClusterFile file) {
        files.replaceOne(Filters.eq("_id", file.key()), fileDoc(file), UPSERT);
    }

    // --- BSON codecs (static + package-private so they're unit-testable without Mongo) -----------

    static Document clusterMetaDoc(ClusterMeta m) {
        return new Document("_id", IDENTITY_ID)
                .append("clusterId", m.clusterId())
                .append("seedSecretBase64", m.seedSecretBase64())
                .append("createdAt", Date.from(m.createdAt()))
                .append("schemaVersion", m.schemaVersion());
    }

    static ClusterMeta toClusterMeta(Document d) {
        return new ClusterMeta(
                d.getString("clusterId"),
                d.getString("seedSecretBase64"),
                d.getDate("createdAt").toInstant(),
                d.getInteger("schemaVersion", ClusterMeta.CURRENT_SCHEMA_VERSION));
    }

    static Document memberDoc(Member m) {
        return new Document("_id", m.nodeId())
                .append("raftAddr", m.raftAddr())
                .append("restAddr", m.restAddr())
                .append("gRPCAddr", m.gRPCAddr())
                .append("label", m.label())
                .append("joinedAt", dateOrNull(m.joinedAt()))
                .append("lastSeen", dateOrNull(m.lastSeen()));
    }

    static Member toMember(Document d) {
        return new Member(
                d.getString("_id"),
                d.getString("raftAddr"),
                d.getString("restAddr"),
                d.getString("gRPCAddr"),
                d.getString("label"),
                instantOrNull(d, "joinedAt"),
                instantOrNull(d, "lastSeen"));
    }

    static Document joinTokenDoc(JoinToken t) {
        return new Document("_id", t.jti())
                .append("hmac", t.hmac())
                .append("label", t.label())
                .append("createdBy", t.createdBy())
                .append("createdAt", dateOrNull(t.createdAt()))
                .append("expiresAt", dateOrNull(t.expiresAt()))
                .append("redeemedAt", dateOrNull(t.redeemedAt()))
                .append("redeemedFrom", t.redeemedFrom())
                .append("redeemedAs", t.redeemedAs())
                .append("revoked", t.revoked())
                .append("revokedBy", t.revokedBy())
                .append("revokedAt", dateOrNull(t.revokedAt()));
    }

    static JoinToken toJoinToken(Document d) {
        return new JoinToken(
                d.getString("_id"),
                d.getString("hmac"),
                d.getString("label"),
                d.getString("createdBy"),
                instantOrNull(d, "createdAt"),
                instantOrNull(d, "expiresAt"),
                instantOrNull(d, "redeemedAt"),
                d.getString("redeemedFrom"),
                d.getString("redeemedAs"),
                d.getBoolean("revoked", false),
                d.getString("revokedBy"),
                instantOrNull(d, "revokedAt"));
    }

    static Document fileDoc(ClusterFile f) {
        return new Document("_id", f.key()).append("sha256", f.sha256()).append("bytes", new Binary(f.bytes()));
    }

    static ClusterFile toClusterFile(Document d) {
        Object raw = d.get("bytes");
        byte[] bytes = raw instanceof Binary bin ? bin.getData() : (byte[]) raw;
        return new ClusterFile(d.getString("_id"), d.getString("sha256"), bytes);
    }

    private static Date dateOrNull(Instant i) {
        return i == null ? null : Date.from(i);
    }

    private static Instant instantOrNull(Document d, String key) {
        Date date = d.getDate(key);
        return date == null ? null : date.toInstant();
    }

    /** Thrown when a write would change the cluster's identity — a split-brain / fork attempt. */
    public static final class ClusterForkException extends RuntimeException {
        public ClusterForkException(String existing, String attempted) {
            super("cluster identity fork rejected: existing clusterId=" + existing + " != attempted=" + attempted);
        }
    }
}
