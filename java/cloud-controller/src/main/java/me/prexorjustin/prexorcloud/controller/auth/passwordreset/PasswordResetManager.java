package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.auth.User;
import me.prexorjustin.prexorcloud.controller.auth.UserStore;
import me.prexorjustin.prexorcloud.security.password.PasswordHasher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates email-token-based password resets. The {@link #request} flow is
 * deliberately silent on user lookup failures so the API can not be used to
 * enumerate accounts; the route always responds 200 regardless of outcome.
 *
 * <p>Tokens are 256-bit random ids issued through
 * {@link PasswordResetTokenStore} with a configurable TTL. {@link #complete}
 * consumes the token (single-use), validates the new password, and updates
 * the user's hash. Sessions issued before the reset are not auto-revoked —
 * the user re-logs in fresh on the dashboard after submitting the form.
 */
public final class PasswordResetManager {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetManager.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserStore userStore;
    private final PasswordResetTokenStore tokenStore;
    private final Mailer mailer;
    private final Duration tokenTtl;
    private final String resetUrlBase;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetManager(
            UserStore userStore,
            PasswordResetTokenStore tokenStore,
            Mailer mailer,
            Duration tokenTtl,
            String resetUrlBase) {
        this.userStore = Objects.requireNonNull(userStore, "userStore");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.mailer = Objects.requireNonNull(mailer, "mailer");
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("tokenTtl must be positive");
        }
        this.tokenTtl = tokenTtl;
        this.resetUrlBase = resetUrlBase == null ? "" : resetUrlBase;
    }

    /**
     * Mint and email a reset link for the user identified by {@code email}.
     * Always succeeds from the caller's point of view — unknown emails and
     * mailer failures are logged internally so the API does not leak account
     * existence. The route handler should log the result and return 200.
     */
    public void request(String email) {
        if (email == null || email.isBlank()) {
            logger.debug("Password-reset request with blank email — ignoring");
            return;
        }
        String normalized = email.toLowerCase();
        Optional<User> userOpt;
        try {
            userOpt = userStore.findByEmail(normalized);
        } catch (IOException e) {
            logger.warn("Password-reset lookup failed for email '{}': {}", normalized, e.toString());
            return;
        }
        if (userOpt.isEmpty()) {
            logger.info("Password-reset requested for unknown email '{}' — ignored (no enumeration leak)", normalized);
            return;
        }
        User user = userOpt.get();
        if (user.passwordHash() == null || user.passwordHash().isEmpty()) {
            logger.info("Password-reset requested for user '{}' with no local password — ignored", user.username());
            return;
        }

        String tokenId = generateTokenId();
        Instant expiresAt = Instant.now().plus(tokenTtl);
        tokenStore.store(new PasswordResetToken(tokenId, user.username(), expiresAt), tokenTtl);

        String url = buildResetUrl(tokenId);
        String body = buildBody(user.username(), url, tokenTtl);
        try {
            mailer.send(user.email(), "Reset your PrexorCloud password", body);
            logger.info("Password-reset link sent for user '{}' (token expires at {})", user.username(), expiresAt);
        } catch (MailerException e) {
            logger.warn("Password-reset mail delivery failed for user '{}': {}", user.username(), e.getMessage());
            // Token remains valid until expiry — operator can read mailer logs / retry. The
            // API still returns 200 to the caller per the no-enumeration policy.
        }
    }

    /**
     * Consume a reset token and replace the user's password hash. Returns a
     * sealed outcome so the route can map invalid tokens / weak passwords to
     * the right HTTP status without leaking which one tripped.
     */
    public CompleteOutcome complete(String tokenId, String newPassword) {
        if (tokenId == null || tokenId.isBlank()) {
            return new CompleteOutcome.InvalidToken();
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            return new CompleteOutcome.WeakPassword("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        Optional<PasswordResetToken> taken = tokenStore.take(tokenId);
        if (taken.isEmpty()) {
            return new CompleteOutcome.InvalidToken();
        }
        PasswordResetToken token = taken.get();
        try {
            Optional<User> userOpt = userStore.getByUsername(token.username());
            if (userOpt.isEmpty()) {
                logger.warn("Password-reset token consumed for missing user '{}' — token discarded", token.username());
                return new CompleteOutcome.InvalidToken();
            }
            String hash = PasswordHasher.hash(newPassword);
            userStore.update(token.username(), null, null, hash);
            logger.info("Password reset completed for user '{}'", token.username());
            return new CompleteOutcome.Success(token.username());
        } catch (IOException e) {
            logger.warn("Password-reset write failed for user '{}': {}", token.username(), e.toString());
            return new CompleteOutcome.InvalidToken();
        }
    }

    private String generateTokenId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String buildResetUrl(String tokenId) {
        String base = resetUrlBase.endsWith("/") ? resetUrlBase.substring(0, resetUrlBase.length() - 1) : resetUrlBase;
        return base + "/auth/reset-password?token=" + URLEncoder.encode(tokenId, StandardCharsets.UTF_8);
    }

    private static String buildBody(String username, String url, Duration ttl) {
        long minutes = Math.max(1, ttl.toMinutes());
        return ("Hello " + username + ",\n\n")
                + "Someone requested a password reset for this account on PrexorCloud.\n"
                + "If that was you, follow the link below to choose a new password:\n\n"
                + url + "\n\n"
                + "This link expires in " + minutes + " minute(s) and can only be used once.\n"
                + "If you did not request a reset, you can ignore this message — your password is unchanged.\n";
    }

    public sealed interface CompleteOutcome {

        record Success(String username) implements CompleteOutcome {}

        record InvalidToken() implements CompleteOutcome {}

        record WeakPassword(String reason) implements CompleteOutcome {}
    }
}
