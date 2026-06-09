package me.prexorjustin.prexorcloud.controller.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;

import io.lettuce.core.api.sync.RedisCommands;

public final class RedisLoginAttemptStore implements LoginAttemptStore {

    private final RedisCommands<String, String> commands;

    public RedisLoginAttemptStore(RedisCommands<String, String> commands) {
        this.commands = commands;
    }

    @Override
    public int recordFailure(String username, Duration window) {
        String key = RedisKeys.loginFailures(username);
        long count = commands.incr(key);
        if (count == 1) {
            commands.expire(key, RedisKeys.sanitizedTtl(window).getSeconds());
        }
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    @Override
    public void clear(String username) {
        commands.del(RedisKeys.loginFailures(username), RedisKeys.loginLock(username));
    }

    @Override
    public void lockUntil(String username, Instant until) {
        long ttlSeconds = Math.max(1L, until.getEpochSecond() - Instant.now().getEpochSecond());
        commands.setex(RedisKeys.loginLock(username), ttlSeconds, Long.toString(until.toEpochMilli()));
    }

    @Override
    public Optional<Instant> lockedUntil(String username) {
        String value = commands.get(RedisKeys.loginLock(username));
        if (value == null) return Optional.empty();
        try {
            long until = Long.parseLong(value);
            if (Instant.now().toEpochMilli() >= until) return Optional.empty();
            return Optional.of(Instant.ofEpochMilli(until));
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
    }
}
