package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import java.util.function.Consumer;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;

/**
 * Dual-write bridge (Phase 4): mirrors each committed cluster Raft entry into {@link
 * MongoClusterStore}, so the Mongo store shadows the authoritative Raft membership state while Raft
 * stays the primary writer. Designed to register on
 * {@code ClusterControlStateMachine.addCommitListener} — which fires only on a <em>successful</em>
 * apply (Raft has already enforced single-use redeem, anti-fork meta, lease ownership, …), so this
 * only ever reflects accepted writes. The state-machine catches listener exceptions, and the store's
 * own guards make every mirror idempotent — important because the listener fires on <em>all</em>
 * controllers for each replicated entry (3× writes, all upserts/no-ops).
 *
 * <p>This is the bridge the plan's flag-gated cutover reads from. Like the rest of the Phase-4
 * substrate it is transitional: Raft (and this bridge) are deleted once the Mongo read path is
 * validated on the fleet. It is <em>not</em> wired to the state machine yet — registration is a
 * one-line, flag-gated step taken at the fleet cutover, kept out of the default path so there is zero
 * behavior change until then.
 *
 * <p>Deliberately NOT mirrored:
 * <ul>
 *   <li><b>config versions</b> ({@code WriteConfigVersion}/{@code SetActiveConfigVersion}) — a later
 *       slice; the store has no config-version collection yet;
 *   <li><b>leases</b> ({@code GrantLease}/{@code RenewLease}/{@code ReleaseLease}) — they fold into
 *       the Mongo leadership lease, not the membership store;
 *   <li><b>TouchMember</b> heartbeats — high-frequency; {@code AddMember} already establishes the
 *       member and the leader-address resolver does not need lastSeen freshness.
 * </ul>
 * The switch is exhaustive over the sealed {@link ClusterEntry}, so a new entry type is a
 * compile-time decision here rather than a silently-dropped write.
 */
public final class MongoClusterShadow implements Consumer<ClusterEntry> {

    private final MongoClusterStore store;

    public MongoClusterShadow(MongoClusterStore store) {
        this.store = store;
    }

    @Override
    public void accept(ClusterEntry entry) {
        switch (entry) {
            case ClusterEntry.SetClusterMeta e -> store.putClusterMeta(e.meta());
            case ClusterEntry.RotateSeed e -> rotateSeed(e);
            case ClusterEntry.AddMember e -> store.putMember(e.member());
            case ClusterEntry.RemoveMember e -> store.removeMember(e.nodeId());
            case ClusterEntry.WriteJoinToken e -> store.putJoinToken(e.token());
            case ClusterEntry.RedeemJoinToken e ->
                store.redeemJoinToken(e.jti(), e.redeemedAt(), e.redeemedFrom(), e.redeemedAs());
            case ClusterEntry.RevokeJoinToken e -> store.revokeJoinToken(e.jti(), e.revokedBy(), e.revokedAt());
            case ClusterEntry.WriteClusterFile e -> store.putClusterFile(e.file());
            case ClusterEntry.DeleteClusterFile e -> store.removeClusterFile(e.key());
            // Not mirrored (see class doc): config versions, leases, heartbeat touches.
            case ClusterEntry.WriteConfigVersion ignored -> {}
            case ClusterEntry.SetActiveConfigVersion ignored -> {}
            case ClusterEntry.TouchMember ignored -> {}
            case ClusterEntry.GrantLease ignored -> {}
            case ClusterEntry.RenewLease ignored -> {}
            case ClusterEntry.ReleaseLease ignored -> {}
        }
    }

    /**
     * Seed rotation changes only the seed on the existing identity. The entry carries the new seed,
     * not the resulting {@link ClusterMeta}, so rebuild it from the shadowed identity (Raft already
     * accepted the rotation, so an identity is present in steady state).
     */
    private void rotateSeed(ClusterEntry.RotateSeed e) {
        store.getClusterMeta()
                .ifPresent(m -> store.putClusterMeta(
                        new ClusterMeta(m.clusterId(), e.newSeedSecretBase64(), m.createdAt(), m.schemaVersion())));
    }
}
