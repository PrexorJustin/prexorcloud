package me.prexorjustin.prexorcloud.controller.cluster;

import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

/**
 * Read-only projection of cluster membership + identity, read from the Mongo cluster store
 * ({@link me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterPlane}).
 *
 * <p>The narrow read seam used by leader resolution and the cluster REST surface; {@link ClusterPlane}
 * extends it with the writes. It covers the member list, cluster identity, and the active config version.
 */
public interface ClusterReadView {

    /** The cluster members, sorted by nodeId. */
    List<Member> listMembers();

    /** Cluster identity ({@code clusterId}, created-at, schema version), or empty before it is stamped. */
    Optional<ClusterMeta> getClusterMeta();

    /** The active cluster-config version, or {@code 0} when none has been written. */
    int getActiveConfigVersion();
}
