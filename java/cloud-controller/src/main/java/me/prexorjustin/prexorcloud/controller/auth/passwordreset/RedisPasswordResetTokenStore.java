package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-backed password-reset token store. Tokens are stored as JSON under
 * {@link RedisKeys#passwordResetToken(String)} with the configured TTL;
 * {@link #take(String)} is implemented as {@code GETDEL} so a token can not be
 * consumed twice.
 */
public final class RedisPasswordResetTokenStore implements PasswordResetTokenStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisPasswordResetTokenStore.class);

    private final RedisCommands<String, String> commands;
    private final ObjectMapper json;

    public RedisPasswordResetTokenStore(RedisCommands<String, String> commands, ObjectMapper json) {
        this.commands = Objects.requireNonNull(commands);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public void store(PasswordResetToken token, Duration ttl) {
        long ttlSeconds = RedisKeys.sanitizedTtl(ttl).getSeconds();
        try {
            ObjectNode node = json.createObjectNode();
            node.put("username", token.username());
            node.put("expiresAt", token.expiresAt().toEpochMilli());
            commands.setex(RedisKeys.passwordResetToken(token.tokenId()), ttlSeconds, json.writeValueAsString(node));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode password-reset token", e);
        }
    }

    @Override
    public Optional<PasswordResetToken> take(String tokenId) {
        if (tokenId == null) return Optional.empty();
        String value = commands.getdel(RedisKeys.passwordResetToken(tokenId));
        if (value == null) return Optional.empty();
        try {
            var node = json.readTree(value);
            Instant expiresAt = Instant.ofEpochMilli(node.get("expiresAt").asLong());
            if (Instant.now().isAfter(expiresAt)) return Optional.empty();
            return Optional.of(
                    new PasswordResetToken(tokenId, node.get("username").asText(), expiresAt));
        } catch (Exception e) {
            logger.warn("Failed to decode password-reset token: {}", e.toString());
            return Optional.empty();
        }
    }
}
