package me.prexorjustin.prexorcloud.controller.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore;

/**
 * In-memory {@link JwtRevocationStore} for development. Revocations live for
 * the lifetime of the controller process and are not shared across
 * controllers. Suitable only for {@code runtime.profile=development}.
 */
final class InMemoryJwtRevocationStore implements JwtRevocationStore {

    private final ConcurrentMap<String, Instant> revoked = new ConcurrentHashMap<>();

    @Override
    public void revoke(String jti, Duration ttl) {
        if (jti == null || jti.isBlank()) return;
        Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(15) : ttl;
        revoked.put(jti, Instant.now().plus(safeTtl));
    }

    @Override
    public boolean isRevoked(String jti) {
        if (jti == null) return false;
        Instant expiry = revoked.get(jti);
        if (expiry == null) return false;
        if (expiry.isBefore(Instant.now())) {
            revoked.remove(jti, expiry);
            return false;
        }
        return true;
    }
}
