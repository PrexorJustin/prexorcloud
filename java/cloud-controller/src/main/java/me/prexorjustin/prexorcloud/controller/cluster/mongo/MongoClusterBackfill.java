package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

/**
 * One-time seed of {@link MongoClusterStore} from the authoritative Raft {@link ClusterControlPlane}
 * (Phase-4 dual-write opt-in). Needed because the Day-0 bootstrap writes — cluster meta, CA files,
 * the founder member, and the v1.0-migration config v1 — all commit inside {@code start()} <em>before</em>
 * the dual-write shadow registers on the state machine, so without this seed a fleet that flips
 * {@code clusterStore=dual} would start out with Mongo missing that bootstrap state and the soak diff
 * would show spurious divergence.
 *
 * <p>Idempotent: every write is an upsert / last-write-wins seed, so re-running on every boot is safe.
 * The seed only populates the shadow store — reads still serve from Raft until the cutover — so it is
 * as side-effect-free for production as the rest of the Phase-4 substrate.
 */
public final class MongoClusterBackfill {

    private MongoClusterBackfill() {}

    /** Copy the full current cluster state from Raft into the Mongo store. */
    public static void seed(ClusterControlPlane plane, MongoClusterStore store) {
        plane.getClusterMeta().ifPresent(store::putClusterMeta);
        for (Member member : plane.listMembers()) {
            store.putMember(member);
        }
        // Full-document upsert preserves redeemed/revoked state (not the single-use redeem CAS).
        for (JoinToken token : plane.listJoinTokens()) {
            store.putJoinToken(token);
        }
        for (ClusterFile file : plane.listClusterFiles()) {
            store.putClusterFile(file);
        }
        // Seed the whole config history at once — it may contain a rebase the per-write guard can't replay.
        store.seedConfigVersions(plane.listConfigVersions(), plane.getActiveConfigVersion());
    }
}
