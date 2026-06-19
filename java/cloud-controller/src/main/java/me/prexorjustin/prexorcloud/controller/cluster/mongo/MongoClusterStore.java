package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
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
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;

/**
 * Mongo-backed persistence for the cluster control-plane state types (Phase 4 of the single-writer
 * rewrite): cluster identity, the member roster, join tokens, the cluster CA files needed to admit a
 * joiner, and the versioned cluster-shared config. This is the durable home that lets cluster join
 * become "register a document in Mongo" instead of completing an embedded-Raft group join — the mTLS
 * join bootstrap (TLSV1_ALERT_CERTIFICATE_REQUIRED) that blocked multi-controller standup is exactly
 * the machinery this retires.
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
 *   <li><b>cluster files</b> (CA cert/key) — last-write-wins upsert;
 *   <li><b>config versions</b> — append-only history with optimistic concurrency: a new version is
 *       admitted only when it is the next ordinal AND its {@code parentVersion} equals the active
 *       version, plus a separate active-version pointer (mirrors the Raft state machine's
 *       {@code applyWriteConfigVersion}/{@code applySetActiveConfigVersion}). Unlike the other types
 *       this lives in a single aggregate document ({@code cluster_config}, {@code _id="config"}:
 *       active pointer + {@code maxVersion} + the {@code versions} array) so the "is-next" and
 *       "parent-matches-active" guards commit atomically in one document update — the same
 *       single-document-CAS idiom the membership writes use, mirroring the in-memory {@code TreeMap}
 *       the Raft snapshot already holds whole.
 * </ul>
 */
public final class MongoClusterStore {

    public static final String IDENTITY_COLLECTION = "cluster_identity";
    public static final String MEMBERS_COLLECTION = "cluster_members";
    public static final String JOIN_TOKENS_COLLECTION = "cluster_join_tokens";
    public static final String FILES_COLLECTION = "cluster_files";
    public static final String CONFIG_COLLECTION = "cluster_config";

    /** Singleton {@code _id} for the cluster-identity document. */
    static final String IDENTITY_ID = "cluster";

    /** Singleton {@code _id} for the config aggregate document (active pointer + version history). */
    static final String CONFIG_ID = "config";

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final MongoCollection<Document> identity;
    private final MongoCollection<Document> members;
    private final MongoCollection<Document> joinTokens;
    private final MongoCollection<Document> files;
    private final MongoCollection<Document> config;

    public MongoClusterStore(MongoDatabase db) {
        this.identity = majority(db, IDENTITY_COLLECTION);
        this.members = majority(db, MEMBERS_COLLECTION);
        this.joinTokens = majority(db, JOIN_TOKENS_COLLECTION);
        this.files = majority(db, FILES_COLLECTION);
        this.config = majority(db, CONFIG_COLLECTION);
    }

    private static MongoCollection<Document> majority(MongoDatabase db, String name) {
        // Cluster membership is small + correctness-critical (anti-fork CAS, single-use redeem), so
        // mirror the lease: majority write + read survives a Mongo primary failover.
        return db.getCollection(name).withWriteConcern(WriteConcern.MAJORITY).withReadConcern(ReadConcern.MAJORITY);
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
        return Optional.ofNullable(files.find(Filters.eq("_id", key)).first()).map(MongoClusterStore::toClusterFile);
    }

    public List<ClusterFile> listClusterFiles() {
        List<ClusterFile> out = new ArrayList<>();
        for (Document doc : files.find().sort(Sorts.ascending("_id"))) {
            out.add(toClusterFile(doc));
        }
        return out;
    }

    public void putClusterFile(ClusterFile file) {
        files.replaceOne(Filters.eq("_id", file.key()), fileDoc(file), UPSERT);
    }

    public boolean removeClusterFile(String key) {
        return files.deleteOne(Filters.eq("_id", key)).getDeletedCount() > 0;
    }

    // --- config versions (optimistic concurrency on parentVersion + active pointer) --------------

    /**
     * Append a new config version, enforcing the same invariant as the Raft state machine: the
     * version must be the next ordinal ({@code maxVersion + 1}) AND its {@code parentVersion} must
     * equal the currently-active version. Both checks commit atomically as a single guarded update on
     * the {@code cluster_config} aggregate, so a stale-parent write (lost a race to another append, or
     * the active pointer moved underneath it) is rejected without a partial write — exactly the
     * conflict the call layer turns into a 409.
     *
     * <p>Idempotent for the dual-write shadow: a replay (or the per-controller fan-out of one
     * committed entry) no longer matches the guard once the version is the head, so it is a silent
     * no-op. The first version bootstraps the aggregate ({@code version=1}, {@code parentVersion=0});
     * a lost bootstrap race surfaces as a duplicate-key on the singleton {@code _id} and is likewise
     * a no-op.
     *
     * @return {@code true} iff this call appended the version.
     */
    public boolean writeConfigVersion(ClusterConfigVersion version) {
        Bson guard = Filters.and(
                Filters.eq("_id", CONFIG_ID),
                Filters.eq("maxVersion", version.version() - 1),
                Filters.eq("activeVersion", version.parentVersion()));
        Bson update = Updates.combine(
                Updates.set("maxVersion", version.version()),
                Updates.set("activeVersion", version.version()),
                Updates.push("versions", configVersionDoc(version)));
        if (config.updateOne(guard, update).getMatchedCount() > 0) {
            return true;
        }
        // No existing aggregate matched the guard. The only legal write against an absent aggregate is
        // the bootstrap (first version, parentVersion=0); anything else is a stale/conflicting append
        // or a replay, and is a no-op. insertOne races safely: a concurrent bootstrap wins the _id and
        // this one duplicate-keys.
        if (version.version() == 1 && version.parentVersion() == 0) {
            try {
                config.insertOne(new Document("_id", CONFIG_ID)
                        .append("maxVersion", 1)
                        .append("activeVersion", 1)
                        .append("versions", List.of(configVersionDoc(version))));
                return true;
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                    return false;
                }
                throw e;
            }
        }
        return false;
    }

    /**
     * Point the active version at an existing version (rollback/rollforward). Rejected (no-op,
     * returns {@code false}) if the version is unknown — mirrors the state machine's
     * {@code VERSION_UNKNOWN}. Idempotent: re-pointing at the current active version still matches.
     */
    public boolean setActiveConfigVersion(int version) {
        return config.updateOne(
                                Filters.and(Filters.eq("_id", CONFIG_ID), Filters.eq("versions.version", version)),
                                Updates.set("activeVersion", version))
                        .getMatchedCount()
                > 0;
    }

    /** The active config version, or {@code 0} when no version has been written yet. */
    public int getActiveConfigVersion() {
        Document d = config.find(Filters.eq("_id", CONFIG_ID)).first();
        return d == null ? 0 : d.getInteger("activeVersion", 0);
    }

    /** The currently-active config version document, or empty when none is active. */
    public Optional<ClusterConfigVersion> getActiveConfigPatch() {
        Document d = config.find(Filters.eq("_id", CONFIG_ID)).first();
        if (d == null) {
            return Optional.empty();
        }
        int active = d.getInteger("activeVersion", 0);
        return active == 0
                ? Optional.empty()
                : configVersionsOf(d).stream()
                        .filter(v -> v.version() == active)
                        .findFirst();
    }

    /** Full version history, ascending by version. */
    public List<ClusterConfigVersion> listConfigVersions() {
        Document d = config.find(Filters.eq("_id", CONFIG_ID)).first();
        return d == null ? List.of() : configVersionsOf(d);
    }

    /**
     * Seed the whole config aggregate at once from an authoritative source (the Phase-4 backfill from
     * Raft). Unlike {@link #writeConfigVersion} this bypasses the per-version optimistic-concurrency
     * guard — a real history can include a rebase (a version whose {@code parentVersion} is neither
     * {@code 0} nor {@code version-1}), which an in-order replay through the guard cannot reproduce.
     * Last-write-wins on the singleton doc, so re-seeding on each boot is idempotent. No-op for an
     * empty history (leaves the aggregate absent → active 0).
     */
    public void seedConfigVersions(List<ClusterConfigVersion> versions, int activeVersion) {
        if (versions.isEmpty()) {
            return;
        }
        List<Document> docs = new ArrayList<>(versions.size());
        int maxVersion = 0;
        for (ClusterConfigVersion v : versions) {
            docs.add(configVersionDoc(v));
            maxVersion = Math.max(maxVersion, v.version());
        }
        Document aggregate = new Document("_id", CONFIG_ID)
                .append("maxVersion", maxVersion)
                .append("activeVersion", activeVersion)
                .append("versions", docs);
        config.replaceOne(Filters.eq("_id", CONFIG_ID), aggregate, UPSERT);
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

    /** A single config-version array element (no {@code _id} — it nests inside the aggregate doc). */
    static Document configVersionDoc(ClusterConfigVersion v) {
        return new Document("version", v.version())
                .append("parentVersion", v.parentVersion())
                .append("mutator", v.mutator())
                .append("mutatedAt", dateOrNull(v.mutatedAt()))
                .append("patch", new Document(v.patch()))
                .append("reason", v.reason());
    }

    static ClusterConfigVersion toConfigVersion(Document d) {
        // The record's canonical constructor copies the patch into an unmodifiable map; an org.bson
        // Document is itself a Map<String, Object>, so it round-trips without an intermediate copy.
        return new ClusterConfigVersion(
                d.getInteger("version"),
                d.getInteger("parentVersion"),
                d.getString("mutator"),
                instantOrNull(d, "mutatedAt"),
                d.get("patch", Document.class),
                d.getString("reason"));
    }

    private static List<ClusterConfigVersion> configVersionsOf(Document aggregate) {
        List<ClusterConfigVersion> out = new ArrayList<>();
        for (Document e : aggregate.getList("versions", Document.class, List.of())) {
            out.add(toConfigVersion(e));
        }
        out.sort(Comparator.comparingInt(ClusterConfigVersion::version));
        return out;
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
