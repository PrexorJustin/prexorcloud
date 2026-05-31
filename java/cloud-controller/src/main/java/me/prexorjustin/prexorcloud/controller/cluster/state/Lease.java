package me.prexorjustin.prexorcloud.controller.cluster.state;

import java.time.Instant;

/**
 * A named leader lease. Used by single-writer work (scheduler, deployment
 * reconciler, DR drill runner, audit pruner) to guarantee that only one
 * controller in the cluster is doing the work at a time. Replaces the v1.0
 * Redis-based leases.
 *
 * <p>Lease validity = {@code now < renewedAt + ttlMillis}. A controller that
 * fails to renew before TTL expiry implicitly releases the lease; another
 * controller can then {@code GrantLease} itself.
 */
public record Lease(
        String name,
        String holder,
        Instant grantedAt,
        long ttlMillis,
        Instant renewedAt) {

    public boolean isValid(Instant now) {
        return holder != null && now.isBefore(renewedAt.plusMillis(ttlMillis));
    }
}
