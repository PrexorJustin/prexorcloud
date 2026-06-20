package me.prexorjustin.prexorcloud.controller.cluster.raft;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.ClusterPlane;
import me.prexorjustin.prexorcloud.controller.cluster.IssuedJoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Lease;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

/**
 * {@link ClusterPlane} backed by the Raft control plane — authoritative under {@code clusterStore=raft}
 * (the default) and {@code dual}. A thin delegate over {@link ClusterControlPlane}.
 */
public final class RaftClusterPlane implements ClusterPlane {

    private final ClusterControlPlane plane;

    public RaftClusterPlane(ClusterControlPlane plane) {
        this.plane = plane;
    }

    @Override
    public List<Member> listMembers() {
        return plane.listMembers();
    }

    @Override
    public Optional<ClusterMeta> getClusterMeta() {
        return plane.getClusterMeta();
    }

    @Override
    public int getActiveConfigVersion() {
        return plane.getActiveConfigVersion();
    }

    @Override
    public Optional<ClusterConfigVersion> getActiveConfigPatch() {
        return plane.getActiveConfigPatch();
    }

    @Override
    public List<ClusterConfigVersion> listConfigVersions() {
        return plane.listConfigVersions();
    }

    @Override
    public Map<String, Object> effectiveConfig() {
        return plane.effectiveConfig();
    }

    @Override
    public Optional<ClusterFile> getClusterFile(String key) {
        return plane.getClusterFile(key);
    }

    @Override
    public List<ClusterFile> listClusterFiles() {
        return plane.listClusterFiles();
    }

    @Override
    public Optional<JoinToken> getJoinToken(String jti) {
        return plane.getJoinToken(jti);
    }

    @Override
    public List<JoinToken> listJoinTokens() {
        return plane.listJoinTokens();
    }

    @Override
    public List<Lease> getLeases() {
        return plane.getLeases();
    }

    @Override
    public void setClusterMeta(ClusterMeta meta) throws IOException {
        plane.setClusterMeta(meta);
    }

    @Override
    public void rotateSeed(String newSeedSecretBase64, String rotatedBy) throws IOException {
        plane.rotateSeed(newSeedSecretBase64, rotatedBy);
    }

    @Override
    public int proposeConfigPatch(int parentVersion, String mutator, Map<String, Object> patch, String reason)
            throws IOException {
        return plane.proposeConfigPatch(parentVersion, mutator, patch, reason);
    }

    @Override
    public void rollbackConfig(int targetVersion, String setBy) throws IOException {
        plane.rollbackConfig(targetVersion, setBy);
    }

    @Override
    public void addMember(Member member) throws IOException {
        plane.addMember(member);
    }

    @Override
    public void removeMember(String nodeId, String reason) throws IOException {
        plane.removeMember(nodeId, reason);
    }

    @Override
    public IssuedJoinToken issueJoinToken(List<String> joinAddrs, Duration ttl, String label, String createdBy)
            throws IOException {
        ClusterControlPlane.IssuedJoinToken issued = plane.issueJoinToken(joinAddrs, ttl, label, createdBy);
        return new IssuedJoinToken(issued.token(), issued.jti(), issued.expiresAt());
    }

    @Override
    public void redeemJoinToken(String jti, Instant redeemedAt, String redeemedFrom, String redeemedAs)
            throws IOException {
        plane.redeemJoinToken(jti, redeemedAt, redeemedFrom, redeemedAs);
    }

    @Override
    public void revokeJoinToken(String jti, String revokedBy) throws IOException {
        plane.revokeJoinToken(jti, revokedBy);
    }

    @Override
    public void writeClusterFile(String key, byte[] bytes) throws IOException {
        plane.writeClusterFile(key, bytes);
    }

    @Override
    public void deleteClusterFile(String key) throws IOException {
        plane.deleteClusterFile(key);
    }
}
