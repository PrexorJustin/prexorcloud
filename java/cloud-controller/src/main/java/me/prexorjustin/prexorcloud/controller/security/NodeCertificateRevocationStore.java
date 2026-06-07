package me.prexorjustin.prexorcloud.controller.security;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Set;

import me.prexorjustin.prexorcloud.security.tls.NodeRevocationCheck;

/**
 * Stores revoked node certificate identifiers (serial number + CN) consulted
 * during gRPC TLS handshakes. Implementations should provide O(1) lookup —
 * {@link #isRevoked(BigInteger, String)} is on the critical path of every
 * incoming connection.
 *
 * <p>Implements {@link NodeRevocationCheck} so the store can be passed
 * directly to {@link me.prexorjustin.prexorcloud.security.tls.ReloadableServerSslContext}.
 */
public interface NodeCertificateRevocationStore extends NodeRevocationCheck {

    /**
     * Mark a certificate revoked. {@code ttl} should match the certificate's
     * remaining validity so storage doesn't grow unbounded after the cert
     * expires naturally.
     *
     * @param serial    leaf certificate serial number
     * @param subjectCn leaf certificate subject CN (used as a fallback / audit
     *                  field; revocation matches against either serial or CN)
     * @param ttl       how long to retain the entry (typically remaining cert
     *                  validity)
     */
    void revoke(BigInteger serial, String subjectCn, Duration ttl);

    /** Remove a revocation entry — used to undo an accidental revoke. */
    void unrevoke(BigInteger serial, String subjectCn);

    /** Snapshot of currently revoked subject CNs (for diagnostics / dashboard). */
    Set<String> revokedSubjectCns();
}
