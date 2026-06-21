package me.prexorjustin.prexorcloud.controller.runtime;

import me.prexorjustin.prexorcloud.controller.auth.InMemoryLoginAttemptStore;
import me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.auth.LoginAttemptStore;
import me.prexorjustin.prexorcloud.controller.config.RuntimeConfig;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.security.InMemoryNodeCertificateRevocationStore;
import me.prexorjustin.prexorcloud.controller.security.NodeCertificateRevocationStore;

/**
 * Development {@link RuntimeServices}. {@link #coordinationEnabled()} is
 * {@code false}; every accessor returns an in-memory equivalent.
 *
 * <p>Out of scope in this mode (documented in {@code architecture.md}):
 * cross-controller JWT revocation, login lockouts visible to peer controllers,
 * password-reset tokens that work on any controller. Single-controller
 * restart-only workflows continue to function.
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
