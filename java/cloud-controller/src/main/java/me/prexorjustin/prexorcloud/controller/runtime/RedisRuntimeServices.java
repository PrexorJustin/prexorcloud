package me.prexorjustin.prexorcloud.controller.runtime;

import java.util.Objects;

import me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.auth.LoginAttemptStore;
import me.prexorjustin.prexorcloud.controller.auth.RedisJwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.auth.RedisLoginAttemptStore;
import me.prexorjustin.prexorcloud.controller.config.RuntimeConfig;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.console.RedisConsoleFloodWindowStore;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.redis.RedisConnection;
import me.prexorjustin.prexorcloud.controller.security.NodeCertificateRevocationStore;
import me.prexorjustin.prexorcloud.controller.security.RedisNodeCertificateRevocationStore;
import me.prexorjustin.prexorcloud.controller.state.RedisRuntimeStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * Production {@link RuntimeServices}. Connects/probes Redis at construction
 * via the supplied {@link RedisConnection} and exposes non-null typed handles
 * for the rest of the wiring graph.
 */
public final class RedisRuntimeServices implements RuntimeServices {

    private final RedisConnection connection;
    private final RedisCommands<String, String> commands;
    private final RedisRuntimeStore runtimeStore;
    private final RedisJwtRevocationStore jwtRevocationStore;
    private final RedisLoginAttemptStore loginAttemptStore;
    private final RedisConsoleFloodWindowStore consoleFloodWindow;
    private final RedisNodeCertificateRevocationStore nodeCertRevocationStore;

    /**
     * Connects to Redis using the URI in {@code connection} and probes the
     * server with a PING. Throws if the probe fails — production must
     * fail fast rather than silently degrade.
     */
    public RedisRuntimeServices(RedisConnection connection, ObjectMapper redisMapper) {
        this.connection = Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(redisMapper, "redisMapper");
        connection.initialize();
        this.commands = connection.sync();
        try {
            String pong = commands.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new IllegalStateException("Redis PING returned unexpected response: " + pong);
            }
        } catch (RuntimeException e) {
            try {
                connection.close();
            } catch (RuntimeException _) {
                // suppressed
            }
            throw e;
        }
        this.runtimeStore = new RedisRuntimeStore(commands, redisMapper);
        this.jwtRevocationStore = new RedisJwtRevocationStore(commands);
        this.loginAttemptStore = new RedisLoginAttemptStore(commands);
        this.consoleFloodWindow = new RedisConsoleFloodWindowStore(commands);
        this.nodeCertRevocationStore = new RedisNodeCertificateRevocationStore(commands);
    }

    @Override
    public String profile() {
        return RuntimeConfig.PRODUCTION;
    }

    @Override
    public boolean coordinationEnabled() {
        return true;
    }

    @Override
    public RedisCommands<String, String> redisCommands() {
        return commands;
    }

    @Override
    public StatefulRedisPubSubConnection<String, String> openPubSubConnection() {
        return connection.pubSubConnection();
    }

    @Override
    public RedisRuntimeStore runtimeStore() {
        return runtimeStore;
    }

    @Override
    public JwtRevocationStore jwtRevocationStore() {
        return jwtRevocationStore;
    }

    @Override
    public LoginAttemptStore loginAttemptStore() {
        return loginAttemptStore;
    }

    @Override
    public ConsoleBuffer.FloodWindowStore consoleFloodWindow() {
        return consoleFloodWindow;
    }

    @Override
    public NodeCertificateRevocationStore nodeCertRevocationStore() {
        return nodeCertRevocationStore;
    }

    /**
     * Wires the controller's {@link MetricsCollector} into stores that produce
     * coordination-store traffic. Called from the bootstrap once metrics are built.
     */
    public void attachMetricsCollector(MetricsCollector metricsCollector) {
        if (metricsCollector != null) {
            jwtRevocationStore.attachMetricsCollector(metricsCollector);
        }
    }

    @Override
    public void close() {
        connection.close();
    }
}
