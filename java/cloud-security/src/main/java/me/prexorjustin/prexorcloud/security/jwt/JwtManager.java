package me.prexorjustin.prexorcloud.security.jwt;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWT token issuance and validation using HS256.
 *
 * <p>Supports a dual-key acceptance window for zero-downtime secret rotation. The
 * controller signs new tokens with {@link #key the active key} but accepts validation
 * against any key in {@link #acceptableKeys}, which includes prior keys until they are
 * rotated out via {@link #rotate(String)}.
 */
public final class JwtManager {

    private static final Logger logger = LoggerFactory.getLogger(JwtManager.class);

    private volatile SecretKey key;
    private final List<SecretKey> acceptableKeys = Collections.synchronizedList(new ArrayList<>());
    private final Duration expiration;
    private final int maxAcceptableKeys;

    /**
     * @param base64Secret
     *            base64-encoded 256-bit secret (required, non-blank)
     * @param expirationMinutes
     *            token expiration in minutes
     * @throws IllegalStateException
     *             if the secret is blank, not valid Base64, or too short
     */
    public JwtManager(String base64Secret, int expirationMinutes) {
        this(base64Secret, expirationMinutes, 2);
    }

    public JwtManager(String base64Secret, int expirationMinutes, int maxAcceptableKeys) {
        if (maxAcceptableKeys < 1) {
            throw new IllegalArgumentException("maxAcceptableKeys must be >= 1");
        }
        this.maxAcceptableKeys = maxAcceptableKeys;
        this.key = decodeKey(base64Secret);
        this.acceptableKeys.add(this.key);
        this.expiration = Duration.ofMinutes(expirationMinutes);
    }

    /**
     * Add a previously valid signing key to the acceptance list without changing the
     * active signing key. Tokens signed with the previous key continue to validate.
     */
    public void addPreviousKey(String base64Secret) {
        SecretKey previous = decodeKey(base64Secret);
        synchronized (acceptableKeys) {
            if (!acceptableKeys.contains(previous)) {
                acceptableKeys.add(previous);
                trimAcceptableKeys();
            }
        }
    }

    /**
     * Rotate the active signing key. Tokens issued from now on use the new key; tokens
     * signed with the previous key continue to validate until {@link #revokeKey(String)}
     * is called or the previous key falls out of the acceptance window.
     */
    public void rotate(String base64NewSecret) {
        SecretKey next = decodeKey(base64NewSecret);
        synchronized (acceptableKeys) {
            this.key = next;
            if (!acceptableKeys.contains(next)) {
                acceptableKeys.add(next);
            }
            trimAcceptableKeys();
        }
        logger.info("JWT signing key rotated; acceptableKeys.size={}", acceptableKeys.size());
    }

    /** Remove a key from the acceptance window. The active signing key cannot be removed. */
    public boolean revokeKey(String base64Secret) {
        SecretKey target = decodeKey(base64Secret);
        synchronized (acceptableKeys) {
            if (target.equals(this.key)) {
                throw new IllegalStateException("cannot revoke the currently active signing key; rotate first");
            }
            return acceptableKeys.remove(target);
        }
    }

    public int acceptableKeyCount() {
        return acceptableKeys.size();
    }

    /**
     * Issue a JWT for the given user.
     *
     * @param username
     *            username (used as subject)
     * @param role
     *            user role (ADMIN, OPERATOR, VIEWER, or custom)
     * @return signed JWT string
     */
    public String issue(String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key)
                .compact();
    }

    /**
     * Validate a JWT and extract claims. The token is accepted if it validates against
     * any key in the acceptance window.
     *
     * @param token
     *            the JWT string
     * @return claims if valid, empty if expired or invalid
     */
    public Optional<Claims> validate(String token) {
        // Snapshot the keys to avoid holding the lock across crypto work.
        List<SecretKey> snapshot;
        synchronized (acceptableKeys) {
            snapshot = new ArrayList<>(acceptableKeys);
        }
        Exception lastFailure = null;
        for (SecretKey candidate : snapshot) {
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(candidate)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
                return Optional.of(claims);
            } catch (Exception ex) {
                lastFailure = ex;
            }
        }
        if (lastFailure != null) {
            logger.debug("JWT validation failed against {} key(s): {}", snapshot.size(), lastFailure.getMessage());
        }
        return Optional.empty();
    }

    private SecretKey decodeKey(String base64Secret) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. This should have been auto-generated during startup. "
                            + "Set 'security.jwtSecret' in controller.yml or generate one with JwtManager.generateSecret()");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT secret is not valid Base64: " + e.getMessage());
        }
        if (decoded.length < 32) {
            throw new IllegalStateException("JWT secret must be at least 256 bits (32 bytes). Got " + decoded.length
                    + " bytes. " + "Generate a valid secret with JwtManager.generateSecret()");
        }
        return Keys.hmacShaKeyFor(decoded);
    }

    private void trimAcceptableKeys() {
        // Caller holds the lock.
        while (acceptableKeys.size() > maxAcceptableKeys) {
            SecretKey oldest = acceptableKeys.get(0);
            if (oldest.equals(this.key)) {
                // Don't drop the active key; drop the next oldest instead.
                if (acceptableKeys.size() > 1) {
                    acceptableKeys.remove(1);
                } else {
                    break;
                }
            } else {
                acceptableKeys.remove(0);
            }
        }
    }

    /**
     * Generate a base64-encoded random secret suitable for JWT signing.
     */
    public static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * CLI entry point to generate a JWT secret.
     */
    public static void main(String[] args) {
        System.out.println(generateSecret());
    }
}
