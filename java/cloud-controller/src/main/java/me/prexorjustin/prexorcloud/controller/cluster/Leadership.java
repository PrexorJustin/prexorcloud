package me.prexorjustin.prexorcloud.controller.cluster;

/**
 * The single-writer authority seam. Exactly one controller is the leader at a time; the leader runs
 * the scheduler/reconciler and is the authority for placing, recovering, and dispatching to every
 * node. Followers do nothing scheduling-wise.
 *
 * <p>"Ownership = leadership": the gates that used to ask "do I hold this node's daemon stream?"
 * ({@code NodeMessageDispatcher.ownsNode}) now ask "am I the leader?". This collapses the divergence
 * bug class — authority no longer flaps when a daemon reconnects to a different controller.
 *
 * <p>Backed by {@link MongoLeaderElector} in production. The interface exists so the gated
 * components (scheduler, placement, recovery) depend only on {@code isLeader()} and are trivially
 * testable, and so leadership is decoupled from the consensus substrate (it could equally sit on the
 * Raft leader during the migration).
 */
public interface Leadership {

    /** Whether this controller currently holds leadership (fencing guard folded in). */
    boolean isLeader();

    /**
     * The fencing epoch of the leadership this controller holds. Monotonic across acquisitions; only
     * meaningful while {@link #isLeader()}. Stamped on fenced daemon commands / lifecycle writes.
     */
    long currentEpoch();

    /**
     * A {@code Leadership} that is always the leader at a fixed epoch — for single-controller installs
     * that never compete, and for tests that don't exercise failover.
     */
    static Leadership alwaysLeader() {
        return new Leadership() {
            @Override
            public boolean isLeader() {
                return true;
            }

            @Override
            public long currentEpoch() {
                return 1L;
            }
        };
    }
}
