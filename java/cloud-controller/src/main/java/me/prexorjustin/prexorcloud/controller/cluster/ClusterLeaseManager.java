package me.prexorjustin.prexorcloud.controller.cluster;

import java.io.IOException;
import java.time.Duration;

import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterWriteConflict;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Raft-backed coarse-grained lease manager. Wraps the {@code GrantLease} /
 * {@code RenewLease} / {@code ReleaseLease} entries on {@link ClusterControlPlane}
 * and surfaces conflicts as quiet booleans instead of exceptions.
 *
 * <p>Use for cluster-wide singleton work — audit pruner, DR drill runner,
 * future scheduler-leader / deployment-reconciler-leader. <em>Don't</em> use
 * for fine-grained partitioning (per-instance, per-group, per-node) — Raft's
 * commit latency would dwarf the work, and the Redis-based
 * {@code DistributedLeaseManager} keeps those cheap. See
 * {@code docs/engineering/cluster-join-plan.md} ("Leader-elected work") for
 * the policy.
 *
 * <p>Holder identity: every controller using this manager constructs it with
 * its own {@code holderId} (the controller's uuid). The state machine
 * compares holder strings on renew/release; a lease held by controller A
 * cannot be renewed by controller B.
 */
public final class ClusterLeaseManager {

    private static final Logger logger = LoggerFactory.getLogger(ClusterLeaseManager.class);

    private final ClusterControlPlane controlPlane;
    private final String holderId;

    public ClusterLeaseManager(ClusterControlPlane controlPlane, String holderId) {
        this.controlPlane = controlPlane;
        this.holderId = holderId;
    }

    /** Identifier this manager claims on grants — exposed for diagnostics and tests. */
    public String holderId() {
        return holderId;
    }

    /**
     * Try to grant the named lease to this controller for {@code ttl}. Returns
     * {@code true} on success (we now hold it, or we re-acquired our own
     * still-valid lease — the state machine collapses self-grants). Returns
     * {@code false} when another holder owns the lease or Raft is unreachable;
     * in both cases the caller should skip the protected work and rely on the
     * next scheduled tick to retry.
     *
     * <p>Raft commit failures are logged at debug. Lease contention is
     * structurally expected (N controllers race on each tick); we don't want
     * one log line per skipped tick per controller.
     */
    public boolean tryAcquire(String name, Duration ttl) {
        try {
            controlPlane.grantLease(name, holderId, ttl.toMillis());
            return true;
        } catch (ClusterWriteConflict e) {
            // LEASE_HELD or any other state-machine rejection. Quiet.
            return false;
        } catch (IOException e) {
            // Raft unavailable — pretend we lost the race; next tick will retry.
            logger.debug("tryAcquire({}) failed: {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * Renew a lease we already hold. Returns {@code true} on success, {@code false}
     * if we don't actually hold the lease (it expired and got taken by another
     * holder, or someone released it on our behalf).
     */
    public boolean tryRenew(String name) {
        try {
            controlPlane.renewLease(name, holderId);
            return true;
        } catch (ClusterWriteConflict e) {
            // LEASE_NOT_HELD / LEASE_UNKNOWN — caller should stop the protected work.
            logger.info("tryRenew({}) rejected: {}", name, e.getMessage());
            return false;
        } catch (IOException e) {
            logger.warn("tryRenew({}) raft failure: {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * Release a lease we hold. Best-effort: rejections (we didn't hold it
     * after all, or it's already gone) are swallowed because the caller is
     * usually in a {@code finally} block and there's nothing useful to do.
     */
    public void release(String name) {
        try {
            controlPlane.releaseLease(name, holderId);
        } catch (ClusterWriteConflict e) {
            logger.debug("release({}) ignored: {}", name, e.getMessage());
        } catch (IOException e) {
            logger.debug("release({}) raft failure: {}", name, e.getMessage());
        }
    }

    /**
     * Convenience: acquire → run → release. Returns {@code true} if the work
     * actually executed (we held the lease for its duration), {@code false}
     * if another controller held the lease and we skipped.
     *
     * <p>The lease is released regardless of whether the work threw. Throws
     * propagate to the caller after release.
     */
    public boolean runUnderLease(String name, Duration ttl, Runnable work) {
        if (!tryAcquire(name, ttl)) {
            return false;
        }
        try {
            work.run();
            return true;
        } finally {
            release(name);
        }
    }
}
