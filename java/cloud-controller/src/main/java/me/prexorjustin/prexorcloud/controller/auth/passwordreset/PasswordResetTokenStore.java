package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage for outstanding password-reset tokens. Tokens are single-use:
 * {@link #take(String)} must atomically return-and-remove so a callback can
 * not be replayed. Implementations enforce TTL and reject expired tokens.
 */
public interface PasswordResetTokenStore {

    void store(PasswordResetToken token, Duration ttl);

    Optional<PasswordResetToken> take(String tokenId);
}
