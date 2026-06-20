package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.ClusterPlane;
import me.prexorjustin.prexorcloud.controller.cluster.IssuedJoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.JoinTokenCodec;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterWriteConflict;
import me.prexorjustin.prexorcloud.controller.cluster.reload.ClusterConfigProjection;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Lease;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

/**
 * {@link ClusterPlane} backed by the Mongo cluster store — the authority under {@code clusterStore=mongo},
 * where Raft is bypassed for cluster state entirely. Reads delegate to {@link MongoClusterStore}; the three
 * logic-bearing writes (config-version proposal, join-token mint, seed rotation) are ported verbatim from
 * {@link me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane} so the admission rules are
 * identical. Conflict-checked writes throw the same {@link ClusterWriteConflict} the Raft plane does, so the
 * REST layer maps rejections without knowing the backing store.
 */
public final class MongoClusterPlane implements ClusterPlane {

    private final MongoClusterStore store;

    public MongoClusterPlane(MongoClusterStore store) {
        this.store = store;
    }

    // --- reads ----------------------------------------------------------------------------------

    @Override
    public List<Member> listMembers() {
        return store.listMembers();
    }

    @Override
    public Optional<ClusterMeta> getClusterMeta() {
        return store.getClusterMeta();
    }

    @Override
    public int getActiveConfigVersion() {
        return store.getActiveConfigVersion();
    }

    @Override
    public Optional<ClusterConfigVersion> getActiveConfigPatch() {
        return store.getActiveConfigPatch();
    }

    @Override
    public List<ClusterConfigVersion> listConfigVersions() {
        return store.listConfigVersions();
    }

    @Override
    public Map<String, Object> effectiveConfig() {
        return ClusterConfigProjection.fold(store.listConfigVersions(), store.getActiveConfigVersion());
    }

    @Override
    public Optional<ClusterFile> getClusterFile(String key) {
        return store.getClusterFile(key);
    }

    @Override
    public List<ClusterFile> listClusterFiles() {
        return store.listClusterFiles();
    }

    @Override
    public Optional<JoinToken> getJoinToken(String jti) {
        return store.getJoinToken(jti);
    }

    @Override
    public List<JoinToken> listJoinTokens() {
        return store.listJoinTokens();
    }

    /** Leases are retired under the Mongo authority (leadership is the Mongo lease), so there are none. */
    @Override
    public List<Lease> getLeases() {
        return List.of();
    }

    // --- writes ---------------------------------------------------------------------------------

    @Override
    public void setClusterMeta(ClusterMeta meta) {
        store.putClusterMeta(meta);
    }

    @Override
    public void rotateSeed(String newSeedSecretBase64, String rotatedBy) {
        ClusterMeta meta = store.getClusterMeta()
                .orElseThrow(() -> new IllegalStateException("cluster meta not stamped — seed rotate rejected"));
        store.putClusterMeta(
                new ClusterMeta(meta.clusterId(), newSeedSecretBase64, meta.createdAt(), meta.schemaVersion()));
    }

    @Override
    public int proposeConfigPatch(int parentVersion, String mutator, Map<String, Object> patch, String reason)
            throws IOException {
        // Optimistic next-ordinal assignment, mirroring ClusterControlPlane#proposeConfigPatch. The
        // store's writeConfigVersion enforces is-next + parent-matches-active atomically; a lost race or
        // stale parent fails the guard and we surface the same conflict the Raft plane does.
        List<ClusterConfigVersion> existing = store.listConfigVersions();
        int proposedVersion =
                existing.isEmpty() ? 1 : existing.get(existing.size() - 1).version() + 1;
        ClusterConfigVersion v =
                new ClusterConfigVersion(proposedVersion, parentVersion, mutator, Instant.now(), patch, reason);
        if (!store.writeConfigVersion(v)) {
            throw new ClusterWriteConflict(
                    "VERSION_NOT_NEXT",
                    "config version " + proposedVersion + " (parent " + parentVersion
                            + ") was rejected — stale parent or a concurrent append won the race");
        }
        return proposedVersion;
    }

    @Override
    public void rollbackConfig(int targetVersion, String setBy) throws IOException {
        if (!store.setActiveConfigVersion(targetVersion)) {
            throw new ClusterWriteConflict(
                    "VERSION_UNKNOWN", "cannot set active config to unknown version " + targetVersion);
        }
    }

    @Override
    public void addMember(Member member) {
        store.putMember(member);
    }

    @Override
    public void removeMember(String nodeId, String reason) {
        // The reason is a Raft-audit field; the Mongo store just drops the member document.
        store.removeMember(nodeId);
    }

    @Override
    public IssuedJoinToken issueJoinToken(List<String> joinAddrs, Duration ttl, String label, String createdBy) {
        ClusterMeta meta = store.getClusterMeta()
                .orElseThrow(() -> new IllegalStateException("cluster meta not stamped — token issue rejected"));
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        byte[] seed = JoinTokenCodec.decodeSeed(meta.seedSecretBase64());
        JoinTokenCodec.Issued issued = JoinTokenCodec.encode(meta.clusterId(), joinAddrs, expiresAt, seed);
        JoinToken record = new JoinToken(
                issued.jti(),
                issued.hmacBase64(),
                label,
                createdBy,
                now,
                expiresAt,
                null,
                null,
                null,
                false,
                null,
                null);
        store.putJoinToken(record);
        return new IssuedJoinToken(issued.token(), issued.jti(), expiresAt);
    }

    @Override
    public void redeemJoinToken(String jti, Instant redeemedAt, String redeemedFrom, String redeemedAs)
            throws IOException {
        if (!store.redeemJoinToken(jti, redeemedAt, redeemedFrom, redeemedAs)) {
            throw new ClusterWriteConflict(
                    "TOKEN_NOT_REDEEMABLE", "join token " + jti + " is unknown, already redeemed, revoked, or expired");
        }
    }

    @Override
    public void revokeJoinToken(String jti, String revokedBy) {
        // Idempotent: revoking an unknown / already-revoked token is a no-op (no conflict surfaced).
        store.revokeJoinToken(jti, revokedBy, Instant.now());
    }

    @Override
    public void writeClusterFile(String key, byte[] bytes) {
        store.putClusterFile(new ClusterFile(key, sha256Hex(bytes), bytes));
    }

    @Override
    public void deleteClusterFile(String key) {
        store.removeClusterFile(key);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
