package me.prexorjustin.prexorcloud.controller.cluster.state;

import java.time.Instant;

/**
 * A single-use cluster join token. {@code hmac} is HMAC-SHA256 of the token's
 * issued-by payload using the cluster's {@code seedSecret}; we persist it so
 * that validation at redemption is local (no second HMAC computation needed).
 *
 * <p>State transitions are tight: a token is created with redemption/revocation
 * fields null, then EITHER redeemed (single use) OR revoked. A redeemed token
 * stays in the collection so replay attempts can be reported with the original
 * redeemer's identity in the error.
 */
public record JoinToken(
        String jti,
        String hmac,
        String label,
        String createdBy,
        Instant createdAt,
        Instant expiresAt,
        Instant redeemedAt,
        String redeemedFrom,
        String redeemedAs,
        boolean revoked,
        String revokedBy,
        Instant revokedAt) {

    public boolean isOutstanding(Instant now) {
        return !revoked && redeemedAt == null && now.isBefore(expiresAt);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
