package me.prexorjustin.prexorcloud.security.tls;

import java.math.BigInteger;

/**
 * Predicate consulted by the gRPC server's trust manager during client
 * certificate verification. Implementations should answer in O(1) — they are
 * called on every TLS handshake.
 */
@FunctionalInterface
public interface NodeRevocationCheck {

    /** Always-allow check used when no revocation store is configured. */
    NodeRevocationCheck NONE = (serial, subjectCn) -> false;

    /**
     * @param serial    the leaf certificate serial number
     * @param subjectCn the leaf certificate subject CN (may be empty if absent)
     * @return {@code true} if the certificate must be rejected
     */
    boolean isRevoked(BigInteger serial, String subjectCn);
}
