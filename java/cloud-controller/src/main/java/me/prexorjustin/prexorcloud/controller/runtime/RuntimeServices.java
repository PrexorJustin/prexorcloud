package me.prexorjustin.prexorcloud.controller.runtime;

import me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.auth.LoginAttemptStore;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.security.NodeCertificateRevocationStore;

/**
 * Aggregate of runtime coordination services. Constructed once at bootstrap
 * based on {@code config.runtime().profile()} so the controller wiring graph
 * never carries raw nullable store dependencies after construction.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@link MongoRuntimeServices} — production. Every durable coordination
 *       store is backed by the controller's authoritative MongoDB.
 *       {@link #coordinationEnabled()} is {@code true} and every coordination
 *       accessor returns a non-null component.</li>
 *   <li>{@link InMemoryRuntimeServices} — development. {@link #coordinationEnabled()}
 *       is {@code false}; the always-on accessors (JWT revocation, login
 *       attempts, console flood window, node-certificate revocation) return
 *       in-memory equivalents so consumer code never branches on null.</li>
 * </ul>
 *
 * <p>The single decision point for "is the shared cross-controller store wired"
 * is {@link #coordinationEnabled()}.
 */
public interface RuntimeServices extends AutoCloseable {

    /** Runtime profile this aggregate was constructed for. */
    String profile();

    /**
     * {@code true} when the shared cross-controller coordination store (MongoDB)
     * is wired (production). {@code false} for the development single-controller
     * in-memory fallback.
     */
    boolean coordinationEnabled();

    /**
     * JWT revocation store. Non-null in both profiles — production returns a
     * Mongo-backed store, development returns a no-op in-memory store so
     * issuance code never branches on null.
     */
    JwtRevocationStore jwtRevocationStore();

    /**
     * Login-attempt counter / lockout store. Non-null in both profiles —
     * production returns a Mongo-backed store (counters and locks survive
     * controller restart and leadership change), development returns an
     * in-memory store. {@code AuthManager} consults this on every login
     * regardless of profile.
     */
    LoginAttemptStore loginAttemptStore();

    /**
     * Console flood-suppression window store. Non-null in both profiles.
     * Leader-only and ephemeral, so it is in-memory in both profiles.
     */
    ConsoleBuffer.FloodWindowStore consoleFloodWindow();

    /**
     * Node certificate revocation store. Non-null in both profiles —
     * production returns a Mongo-backed store, development returns an
     * in-memory store so the gRPC trust manager can consult a single
     * non-nullable handle.
     */
    NodeCertificateRevocationStore nodeCertRevocationStore();

    @Override
    void close();
}
