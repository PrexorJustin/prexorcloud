package me.prexorjustin.prexorcloud.controller.cluster.raft;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.JoinTokenCodec;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Lease;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

/**
 * Typed façade over the cluster control plane. Writes go through Raft (the
 * underlying {@link RaftBootstrap}); reads return immutable snapshots of the
 * local state machine projection.
 *
 * <p>Multi-node linearizable reads (Ratis ReadIndex) come in a later phase
 * along with the gRPC membership protocol; for now reads are sequentially
 * consistent — fast and correct for everything the dashboard does, but does
 * not guarantee real-time visibility across followers.
 *
 * <p>Methods that perform conflict-checked writes (config patches with stale
 * parentVersion, redeem of an already-redeemed token, grant of a held lease)
 * throw {@link ClusterWriteConflict} carrying the rejection {@code code} from
 * {@link ClusterEntry.Reply}.
 */
public final class ClusterControlPlane {

    private final RaftBootstrap raft;
    private final ClusterControlStateMachine sm;

    public ClusterControlPlane(RaftBootstrap raft, ClusterControlStateMachine sm) {
        this.raft = raft;
        this.sm = sm;
    }

    // --- Reads (local projection) ---

    public Optional<ClusterMeta> getClusterMeta() {
        return sm.getClusterMeta();
    }

    public int getActiveConfigVersion() {
        return sm.getActiveConfigVersion();
    }

    public Optional<ClusterConfigVersion> getActiveConfigPatch() {
        return sm.getActiveConfigPatch();
    }

    public List<ClusterConfigVersion> listConfigVersions() {
        return sm.listConfigVersions();
    }

    public List<Member> listMembers() {
        return sm.listMembers();
    }

    public Optional<JoinToken> getJoinToken(String jti) {
        return sm.getJoinToken(jti);
    }

    public List<JoinToken> listJoinTokens() {
        return sm.listJoinTokens();
    }

    public Optional<Lease> getLease(String name) {
        return sm.getLease(name);
    }

    public Optional<ClusterFile> getClusterFile(String key) {
        return sm.getClusterFile(key);
    }

    public List<ClusterFile> listClusterFiles() {
        return sm.listClusterFiles();
    }

    // --- Writes (through Raft) ---

    public void setClusterMeta(ClusterMeta meta) throws IOException {
        submitOrThrow(new ClusterEntry.SetClusterMeta(meta));
    }

    public void rotateSeed(String newSeedSecretBase64, String rotatedBy) throws IOException {
        submitOrThrow(new ClusterEntry.RotateSeed(newSeedSecretBase64, rotatedBy, Instant.now()));
    }

    /**
     * Propose a new config version. {@code parentVersion} must equal the current
     * active version; mismatch throws {@link ClusterWriteConflict} so the caller can
     * surface HTTP 409 and let the operator refresh + rebase. Returns the new
     * active version number.
     */
    public int proposeConfigPatch(int parentVersion, String mutator, Map<String, Object> patch, String reason)
            throws IOException {
        // Optimistic version assignment: next = lastSeen + 1. If a concurrent writer beats
        // us to it, the state machine rejects with VERSION_NOT_NEXT and the caller retries.
        List<ClusterConfigVersion> existing = sm.listConfigVersions();
        int proposedVersion =
                existing.isEmpty() ? 1 : existing.get(existing.size() - 1).version() + 1;
        ClusterConfigVersion v =
                new ClusterConfigVersion(proposedVersion, parentVersion, mutator, Instant.now(), patch, reason);
        ClusterEntry.Reply reply = submit(new ClusterEntry.WriteConfigVersion(v));
        if (!reply.ok()) {
            throw new ClusterWriteConflict(reply.code(), reply.message());
        }
        Object versionObj = reply.data().get("version");
        return versionObj instanceof Number n ? n.intValue() : proposedVersion;
    }

    public void rollbackConfig(int targetVersion, String setBy) throws IOException {
        submitOrThrow(new ClusterEntry.SetActiveConfigVersion(targetVersion, setBy, Instant.now()));
    }

    public void addMember(Member member) throws IOException {
        submitOrThrow(new ClusterEntry.AddMember(member));
    }

    public void removeMember(String nodeId, String reason) throws IOException {
        submitOrThrow(new ClusterEntry.RemoveMember(nodeId, reason, Instant.now()));
    }

    public void touchMember(String nodeId, Instant lastSeen) throws IOException {
        submitOrThrow(new ClusterEntry.TouchMember(nodeId, lastSeen));
    }

    public void writeJoinToken(JoinToken token) throws IOException {
        submitOrThrow(new ClusterEntry.WriteJoinToken(token));
    }

    /**
     * Mint a new cluster join token: HMAC the payload with the cluster seed,
     * commit the redemption record to Raft, and return the wire token string.
     *
     * @return record carrying the wire token and the JTI (which is the token's
     *         globally unique identifier).
     * @throws IOException if Raft is unavailable
     * @throws IllegalStateException if the cluster meta has not been stamped yet
     */
    public IssuedJoinToken issueJoinToken(
            List<String> joinAddrs, java.time.Duration ttl, String label, String createdBy) throws IOException {
        ClusterMeta meta = getClusterMeta()
                .orElseThrow(() -> new IllegalStateException("cluster meta not stamped — issue rejected"));
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
        writeJoinToken(record);
        return new IssuedJoinToken(issued.token(), issued.jti(), expiresAt);
    }

    public record IssuedJoinToken(String token, String jti, Instant expiresAt) {}

    /**
     * Redeem a single-use join token. Throws {@link ClusterWriteConflict} on
     * unknown / revoked / already-redeemed / expired tokens — the wizard surfaces
     * the rejection code to the operator.
     */
    public void redeemJoinToken(String jti, Instant redeemedAt, String redeemedFrom, String redeemedAs)
            throws IOException {
        submitOrThrow(new ClusterEntry.RedeemJoinToken(jti, redeemedAt, redeemedFrom, redeemedAs));
    }

    public void revokeJoinToken(String jti, String revokedBy) throws IOException {
        submitOrThrow(new ClusterEntry.RevokeJoinToken(jti, revokedBy, Instant.now()));
    }

    public void grantLease(String name, String holder, long ttlMillis) throws IOException {
        submitOrThrow(new ClusterEntry.GrantLease(name, holder, Instant.now(), ttlMillis));
    }

    public void renewLease(String name, String holder) throws IOException {
        submitOrThrow(new ClusterEntry.RenewLease(name, holder, Instant.now()));
    }

    public void releaseLease(String name, String holder) throws IOException {
        submitOrThrow(new ClusterEntry.ReleaseLease(name, holder));
    }

    /** Stamp a binary blob into the cluster Raft state under {@code key}. */
    public void writeClusterFile(String key, byte[] bytes) throws IOException {
        ClusterFile file = new ClusterFile(key, sha256Hex(bytes), bytes);
        submitOrThrow(new ClusterEntry.WriteClusterFile(file));
    }

    public void deleteClusterFile(String key) throws IOException {
        submitOrThrow(new ClusterEntry.DeleteClusterFile(key));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Force a state-machine snapshot. */
    public long takeSnapshot() throws IOException {
        return raft.takeSnapshot();
    }

    private ClusterEntry.Reply submit(ClusterEntry entry) throws IOException {
        return ClusterEntry.Reply.decode(raft.submitRaw(entry.encode()));
    }

    private void submitOrThrow(ClusterEntry entry) throws IOException {
        ClusterEntry.Reply reply = submit(entry);
        if (!reply.ok()) {
            throw new ClusterWriteConflict(reply.code(), reply.message());
        }
    }
}
