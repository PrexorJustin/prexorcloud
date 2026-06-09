package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local password-reset token store for development. Production must
 * use the Redis-backed implementation so a reset link can be consumed on a
 * different controller than the one that minted it.
 */
public final class InMemoryPasswordResetTokenStore implements PasswordResetTokenStore {

    private final ConcurrentHashMap<String, PasswordResetToken> tokens = new ConcurrentHashMap<>();

    @Override
    public void store(PasswordResetToken token, Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl);
        tokens.put(token.tokenId(), new PasswordResetToken(token.tokenId(), token.username(), expiresAt));
    }

    @Override
    public Optional<PasswordResetToken> take(String tokenId) {
        if (tokenId == null) return Optional.empty();
        PasswordResetToken t = tokens.remove(tokenId);
        if (t == null) return Optional.empty();
        if (Instant.now().isAfter(t.expiresAt())) return Optional.empty();
        return Optional.of(t);
    }

    public int size() {
        return tokens.size();
    }
}
