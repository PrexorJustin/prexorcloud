package me.prexorjustin.prexorcloud.controller.scheduler;

/**
 * The single-writer authority seam the Scheduler exposes to its collaborators
 * (StartRetryOrchestrator, placement, deployment reconciler).
 *
 * <p>Under the single-writer control plane only the leader emits cluster-mutating
 * actions for a group; followers do nothing. This contract lets collaborators ask
 * without depending on the leadership substrate directly:
 *
 * <ol>
 *   <li>{@link #ownsGroupLease} — fast check before scheduling work for a group.
 *   <li>{@link #ensureLeaseCurrent} — fencing re-check immediately before a
 *       cluster-mutating action, to detect a leadership change mid-work and bail.
 * </ol>
 *
 * <p>The Scheduler is the canonical implementation; both methods fold onto
 * {@code leadership.isLeader()}. Tests can substitute an "always granted" stub.
 */
public interface LeaseGate {

    /**
     * Whether this controller may act on the group — i.e. it currently holds
     * leadership. The {@code groupName} is retained for call-site clarity and log
     * diagnostics; leadership itself is cluster-wide, not per-group.
     */
    boolean ownsGroupLease(String groupName);

    /**
     * Fencing re-check before a cluster-mutating action. Returns {@code true} when
     * it's still safe to mutate; {@code false} when leadership was lost mid-work and
     * the caller should bail out.
     *
     * @param groupName the group the work belongs to (for log diagnostics)
     * @param action    short label of what's about to happen
     */
    boolean ensureLeaseCurrent(String groupName, String action);
}
