package me.prexorjustin.prexorcloud.controller.runtime;

import java.util.Objects;

import me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.auth.LoginAttemptStore;
import me.prexorjustin.prexorcloud.controller.auth.MongoJwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.auth.MongoLoginAttemptStore;
import me.prexorjustin.prexorcloud.controller.config.RuntimeConfig;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.security.MongoNodeCertificateRevocationStore;
import me.prexorjustin.prexorcloud.controller.security.NodeCertificateRevocationStore;

import com.mongodb.client.MongoDatabase;

/**
 * Production {@link RuntimeServices}. Every durable coordination store is
 * Mongo-backed and shares the controller's authoritative {@link MongoDatabase} —
 * the single store the single-writer control plane keeps after the Redis/Valkey
 * removal. A revoked JWT, a login lockout, a node-certificate revocation and an
 * in-flight password-reset token therefore all survive a controller restart and
 * a leadership change without a second datastore.
 *
 * <p>The console flood-suppression window is the one exception: it is leader-only
 * and purely ephemeral (a per-instance rate window on console output), so it stays
 * in process memory rather than paying a Mongo write per console line. A fresh
 * leader simply starts its flood windows empty.
 */
public final class MongoRuntimeServices implements RuntimeServices {

    private final MongoJwtRevocationStore jwtRevocationStore;
    private final MongoLoginAttemptStore loginAttemptStore;
    private final ConsoleBuffer.FloodWindowStore consoleFloodWindow;
    private final MongoNodeCertificateRevocationStore nodeCertRevocationStore;

    public MongoRuntimeServices(MongoDatabase db) {
        Objects.requireNonNull(db, "db");
        this.jwtRevocationStore = new MongoJwtRevocationStore(db);
        this.loginAttemptStore = new MongoLoginAttemptStore(db);
        this.nodeCertRevocationStore = new MongoNodeCertificateRevocationStore(db);
        this.consoleFloodWindow = ConsoleBuffer.newInMemoryFloodWindowStore();
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
     * Wires the controller's {@link MetricsCollector} into the stores that emit
     * coordination metrics. Called from the bootstrap once metrics are built.
     */
    public void attachMetricsCollector(MetricsCollector metricsCollector) {
        if (metricsCollector != null) {
            jwtRevocationStore.attachMetricsCollector(metricsCollector);
        }
    }

    @Override
    public void close() {
        // The MongoDatabase/MongoClient is owned and closed by the bootstrap.
    }
}
