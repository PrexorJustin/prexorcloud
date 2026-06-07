package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

import java.time.Instant;

/**
 * Single-use password-reset token. The {@code tokenId} is the opaque secret
 * delivered to the user; the store keys entries by {@code tokenId} and the
 * record carries enough context to swap the user's password on consume.
 */
public record PasswordResetToken(String tokenId, String username, Instant expiresAt) {}
