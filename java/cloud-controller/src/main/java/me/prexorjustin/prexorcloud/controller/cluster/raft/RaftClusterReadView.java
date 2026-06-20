package me.prexorjustin.prexorcloud.controller.cluster.raft;

import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.ClusterReadView;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

/**
 * {@link ClusterReadView} backed by the Raft control plane — the authoritative source under
 * {@code clusterStore=raft} (the default) and {@code dual}. A thin delegate over
 * {@link ClusterControlPlane}'s local state-machine projection.
 */
public final class RaftClusterReadView implements ClusterReadView {

    private final ClusterControlPlane plane;

    public RaftClusterReadView(ClusterControlPlane plane) {
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
}
