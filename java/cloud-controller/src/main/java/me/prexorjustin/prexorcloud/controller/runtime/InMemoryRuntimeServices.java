package me.prexorjustin.prexorcloud.controller.runtime;

import me.prexorjustin.prexorcloud.controller.auth.InMemoryLoginAttemptStore;
import me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.auth.LoginAttemptStore;
import me.prexorjustin.prexorcloud.controller.config.RuntimeConfig;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.security.InMemoryNodeCertificateRevocationStore;
import me.prexorjustin.prexorcloud.controller.security.NodeCertificateRevocationStore;
import me.prexorjustin.prexorcloud.controller.state.RedisRuntimeStore;

import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * Development {@link RuntimeServices}. {@link #coordinationEnabled()} is
 * {@code false}; coordination-only accessors return {@code null} and
 * always-on accessors return in-memory no-op equivalents.
 *
 * <p>Out of scope in this mode (documented in {@code architecture.md}):
 * cross-controller leases, JWT revocation across controllers, persisted
 * runtime snapshots, SSE replay across restart, distributed rate limiting.
 * Single-controller restart-only workflows continue to function.
 */
public final class InMemoryRuntimeServices implements RuntimeServices {

    private final JwtRevocationStore jwtRevocationStore = new InMemoryJwtRevocationStore();
    private final LoginAttemptStore loginAttemptStore = new InMemoryLoginAttemptStore();
    private final ConsoleBuffer.FloodWindowStore consoleFloodWindow = ConsoleBuffer.newInMemoryFloodWindowStore();
    private final NodeCertificateRevocationStore nodeCertRevocationStore = new InMemoryNodeCertificateRevocationStore();

    public InMemoryRuntimeServices() {}

    @Override
    public String profile() {
        return RuntimeConfig.DEVELOPMENT;
    }

    @Override
    public boolean coordinationEnabled() {
        return false;
    }

    @Override
    public RedisCommands<String, String> redisCommands() {
        return null;
    }

    @Override
    public StatefulRedisPubSubConnection<String, String> openPubSubConnection() {
        return null;
    }

    @Override
    public RedisRuntimeStore runtimeStore() {
        return null;
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

    @Override
    public void close() {
        // no-op
    }
}
