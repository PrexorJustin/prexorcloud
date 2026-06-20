package me.prexorjustin.prexorcloud.controller.cluster;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Lease;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

/**
 * Full read + write seam over cluster control-plane state, backed by the Mongo cluster store
 * ({@link me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterPlane}). Cluster state is
 * Mongo-authoritative; only the leader (the controller holding the leadership lease) calls the writes.
 *
 * <p>Extends {@link ClusterReadView} so the read-only consumers (leader resolution, the cluster REST
 * surface) can depend on the narrow read seam. Conflict-checked writes throw {@link ClusterWriteConflict}
 * (an {@link IOException}) with a stable {@code code} so the REST layer maps rejections deterministically.
 *
 * <p>Leases ({@code grantLease}/{@code renewLease}/{@code releaseLease}) are absent — leadership is the
 * Mongo lease, so {@link #getLeases()} is the only lease surface and always returns empty.
 */
public interface ClusterPlane extends ClusterReadView {

    // --- reads (beyond ClusterReadView) ---------------------------------------------------------

    Optional<ClusterConfigVersion> getActiveConfigPatch();

    List<ClusterConfigVersion> listConfigVersions();

    /** The effective cluster-shared config: the active version's patch folded onto its parent chain. */
    Map<String, Object> effectiveConfig();

    Optional<ClusterFile> getClusterFile(String key);

    List<ClusterFile> listClusterFiles();

    Optional<JoinToken> getJoinToken(String jti);

    List<JoinToken> listJoinTokens();

    /** Lease holders. Always empty — leadership is the Mongo lease, not a cluster-state lease. */
    List<Lease> getLeases();

    // --- writes (only the leader calls these) ---------------------------------------------------

    void setClusterMeta(ClusterMeta meta) throws IOException;

    void rotateSeed(String newSeedSecretBase64, String rotatedBy) throws IOException;

    /**
     * Propose a new config version. {@code parentVersion} must equal the current active version;
     * mismatch throws {@code ClusterWriteConflict}. Returns the new active version number.
     */
    int proposeConfigPatch(int parentVersion, String mutator, Map<String, Object> patch, String reason)
            throws IOException;

    void rollbackConfig(int targetVersion, String setBy) throws IOException;

    void addMember(Member member) throws IOException;

    void removeMember(String nodeId, String reason) throws IOException;

    IssuedJoinToken issueJoinToken(List<String> joinAddrs, Duration ttl, String label, String createdBy)
            throws IOException;

    void redeemJoinToken(String jti, Instant redeemedAt, String redeemedFrom, String redeemedAs) throws IOException;

    void revokeJoinToken(String jti, String revokedBy) throws IOException;

    void writeClusterFile(String key, byte[] bytes) throws IOException;

    void deleteClusterFile(String key) throws IOException;
}
