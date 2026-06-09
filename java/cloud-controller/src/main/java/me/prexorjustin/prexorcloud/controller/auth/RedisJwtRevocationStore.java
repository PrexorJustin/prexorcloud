package me.prexorjustin.prexorcloud.controller.auth;

import java.time.Duration;

import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;

import io.lettuce.core.api.sync.RedisCommands;

public final class RedisJwtRevocationStore implements JwtRevocationStore {

    private final RedisCommands<String, String> commands;
    private volatile MetricsCollector metricsCollector;

    public RedisJwtRevocationStore(RedisCommands<String, String> commands) {
        this.commands = commands;
    }

    public void attachMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    @Override
    public void revoke(String jti, Duration ttl) {
        commands.setex(RedisKeys.jwtRevoked(jti), RedisKeys.sanitizedTtl(ttl).getSeconds(), "1");
        MetricsCollector mc = metricsCollector;
        if (mc != null) mc.recordJwtRevocation();
    }

    @Override
    public boolean isRevoked(String jti) {
        return commands.exists(RedisKeys.jwtRevoked(jti)) > 0;
    }
}
