package me.prexorjustin.prexorcloud.controller.runtime;

import me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.auth.LoginAttemptStore;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.security.NodeCertificateRevocationStore;
import me.prexorjustin.prexorcloud.controller.state.RedisRuntimeStore;

import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * Aggregate of runtime coordination services. Constructed once at bootstrap
 * based on {@code config.runtime().profile()} so the controller wiring graph
 * never carries raw nullable Redis-shaped dependencies after construction.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@link RedisRuntimeServices} — production. Connects/probes the
 *       Redis-protocol coordination store at construction and throws on
 *       failure. {@link #coordinationEnabled()} is {@code true} and every
 *       coordination accessor returns a non-null component.</li>
 *   <li>{@link InMemoryRuntimeServices} — development. {@link #coordinationEnabled()}
 *       is {@code false}; coordination-only accessors return {@code null}.
 *       Always-on accessors (JWT revocation, console flood window) return
 *       in-memory no-op equivalents so consumer code never branches on
 *       null.</li>
 * </ul>
 *
 * <p>Components that previously branched on {@code redis != null} should
 * accept a {@link RuntimeServices} reference and call the typed accessors
 * below. The single decision point is {@link #coordinationEnabled()}.
 */
public interface RuntimeServices extends AutoCloseable {

    /** Runtime profile this aggregate was constructed for. */
    String profile();

    /**
     * {@code true} when the shared cross-controller coordination store is
     * wired (production). {@code false} for the development single-controller
     * fallback.
     */
    boolean coordinationEnabled();

    /**
     * Raw Redis-protocol commands handle. Non-null when {@link #coordinationEnabled()}
     * returns {@code true}; {@code null} otherwise. Callers that need raw
     * access (SSE replay/tickets, rate-limit, scaling cooldown, keyspace
     * inspector) must guard with {@link #coordinationEnabled()}.
     */
    RedisCommands<String, String> redisCommands();

    /**
     * Open a fresh Redis-protocol pub/sub connection. Used by the event
     * bridge for separate publish and subscribe pairs. Non-null when
     * {@link #coordinationEnabled()} is {@code true}; {@code null} otherwise.
     */
    StatefulRedisPubSubConnection<String, String> openPubSubConnection();

    /**
     * Cluster-state runtime hydration store. Non-null when
     * {@link #coordinationEnabled()} is {@code true}; {@code null} otherwise.
     */
    RedisRuntimeStore runtimeStore();

    /**
     * JWT revocation store. Non-null in both profiles — production returns a
     * Redis-backed store, development returns a no-op in-memory store so
     * issuance code never branches on null.
     */
    JwtRevocationStore jwtRevocationStore();

    /**
     * Login-attempt counter / lockout store. Non-null in both profiles —
     * production returns a Redis-backed store (counters and locks survive
     * controller restart and are visible to peer controllers), development
     * returns an in-memory store. {@code AuthManager} consults this on every
     * login regardless of profile.
     */
    LoginAttemptStore loginAttemptStore();

    /**
     * Console flood-suppression window store. Non-null in both profiles.
     */
    ConsoleBuffer.FloodWindowStore consoleFloodWindow();

    /**
     * Node certificate revocation store. Non-null in both profiles —
     * production returns a Redis-backed store, development returns an
     * in-memory store so the gRPC trust manager can consult a single
     * non-nullable handle.
     */
    NodeCertificateRevocationStore nodeCertRevocationStore();

    @Override
    void close();
}
