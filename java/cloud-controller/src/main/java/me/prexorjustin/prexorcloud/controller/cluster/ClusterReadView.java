package me.prexorjustin.prexorcloud.controller.cluster;

import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

/**
 * Read-only projection of cluster membership + identity, sourced from whichever backing store the
 * single-writer rewrite's Phase-4 migration is reading from ({@code clusterStore}):
 *
 * <ul>
 *   <li>{@code RAFT}/{@code DUAL} → {@link me.prexorjustin.prexorcloud.controller.cluster.raft.RaftClusterPlane}
 *       reads the Raft state-machine projection (authoritative).</li>
 *   <li>{@code MONGO} → {@link me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterPlane}
 *       reads the Mongo cluster store (the read cutover).</li>
 * </ul>
 *
 * <p>The read-only narrow seam; {@link ClusterPlane} extends it with the writes.
 *
 * <p>This seam covers only the state that the dual-write shadow mirrors into Mongo and that the
 * consumer-facing surface needs: the member list, cluster identity, and the active config version.
 * Raft leases are deliberately NOT mirrored, so lease reads stay on the control plane directly.
 * Writes, the Raft peer-group reconciler, and the join path also stay on Raft until their own
 * cutover slices land — this is reads only.
 */
public interface ClusterReadView {

    /** The cluster members, sorted by nodeId. */
    List<Member> listMembers();

    /** Cluster identity ({@code clusterId}, created-at, schema version), or empty before it is stamped. */
    Optional<ClusterMeta> getClusterMeta();

    /** The active cluster-config version, or {@code 0} when none has been written. */
    int getActiveConfigVersion();
}
