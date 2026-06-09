package me.prexorjustin.prexorcloud.controller.scheduler;

import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;

/**
 * The cluster-wide group-lease abstraction the Scheduler exposes to its
 * collaborators (StartRetryOrchestrator, deployment reconciler, etc.).
 *
 * <p>Active-active controllers each see every group, but only the
 * controller that holds the per-group lease may emit cluster-mutating
 * actions for that group (start-retry resends, deployment rolling
 * updates, drain dispatches). This three-method contract is the seam
 * collaborators use to:
 *
 * <ol>
 *   <li>{@link #ownsGroupLease} — fast check before scheduling work.
 *   <li>{@link #acquireGroupLease} — actually take the lease for a unit
 *       of work; nullable when no lease manager is configured.
 *   <li>{@link #ensureLeaseCurrent} — fencing-token check immediately
 *       before mutating the cluster, to detect lease expiry mid-work.
 * </ol>
 *
 * <p>The Scheduler is the canonical implementation; tests can substitute
 * a "lease always granted" stub.
 */
public interface LeaseGate {

    /**
     * Best-effort check whether this controller currently owns the group's
     * lease. Calling this does not extend or refresh the lease.
     */
    boolean ownsGroupLease(String groupName);

    /**
     * Try to acquire the group's lease. Returns {@code null} when the
     * controller is running without coordination (no Redis/Valkey) — in
     * that mode every controller "owns" every group.
     */
    DistributedLeaseManager.Lease acquireGroupLease(String groupName);

    /**
     * Fencing-token check before a cluster-mutating action. Returns
     * {@code true} when it's still safe to mutate; {@code false} when
     * the lease has been lost mid-work and the caller should bail out.
     *
     * @param lease     the lease the caller acquired earlier (may be null
     *                  when no lease manager is configured)
     * @param groupName the group the work belongs to (for log diagnostics)
     * @param action    short label of what's about to happen
     */
    boolean ensureLeaseCurrent(DistributedLeaseManager.Lease lease, String groupName, String action);
}
