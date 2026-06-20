package me.prexorjustin.prexorcloud.daemon.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import me.prexorjustin.prexorcloud.common.concurrent.Backoff;
import me.prexorjustin.prexorcloud.common.util.FilePermissions;
import me.prexorjustin.prexorcloud.daemon.config.ControllerEndpoint;
import me.prexorjustin.prexorcloud.protocol.BootstrapServiceGrpc;
import me.prexorjustin.prexorcloud.protocol.ExchangeJoinTokenRequest;
import me.prexorjustin.prexorcloud.security.tls.ClientTlsCredentials;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles first-time node bootstrap: exchanges a join token for a certificate.
 *
 * <p>Given a seed list of controllers, {@link #bootstrap} sweeps them and enrolls against the first
 * that answers, so a single controller being down doesn't block a fresh node from joining. The whole
 * sweep is retried with backoff for transient failures (so a coordinated controller restart doesn't
 * crash-loop the node under systemd's {@code RestartSec}); a permanent rejection — a bad join token —
 * fails fast rather than hammering the cluster.</p>
 */
public final class BootstrapManager {

    private static final Logger logger = LoggerFactory.getLogger(BootstrapManager.class);

    /** Per-RPC deadline for one ExchangeJoinToken attempt. */
    private static final int EXCHANGE_DEADLINE_SECONDS = 30;

    private final List<ControllerEndpoint> controllers;
    private final Path certDir;
    private final JoinTokenExchange exchange;

    public BootstrapManager(List<ControllerEndpoint> controllers, Path certDir) {
        this(controllers, certDir, null);
    }

    /** Single-endpoint convenience constructor (back-compat). */
    public BootstrapManager(String controllerHost, int controllerPort, Path certDir) {
        this(List.of(new ControllerEndpoint(controllerHost, controllerPort)), certDir);
    }

    /** Test seam: inject the per-controller exchange step so the sweep/retry logic can run offline. */
    BootstrapManager(List<ControllerEndpoint> controllers, Path certDir, JoinTokenExchange exchange) {
        if (controllers == null || controllers.isEmpty()) {
            throw new IllegalArgumentException("at least one controller endpoint is required for bootstrap");
        }
        this.controllers = List.copyOf(controllers);
        this.certDir = certDir;
        this.exchange = exchange != null ? exchange : this::grpcExchange;
    }

    public Path nodePkcs12Path() {
        return certDir.resolve("node.p12");
    }

    public Path caPemPath() {
        return certDir.resolve("ca.pem");
    }

    public Path passwordPath() {
        return certDir.resolve(".node-password");
    }

    public boolean isBootstrapped() {
        return Files.exists(nodePkcs12Path());
    }

    /**
     * Exchange a join token with the cluster to obtain a node certificate. Sweeps the configured
     * controllers (first success wins) and retries the sweep with backoff on transient failure.
     * Connects WITHOUT a trusted CA for bootstrap (the join token authenticates the request).
     *
     * @throws Exception the permanent failure (e.g. bad token), or — if every controller stays
     *     unreachable across all retries — the last transient failure.
     */
    public void bootstrap(String joinToken, String nodeId) throws Exception {
        bootstrap(joinToken, nodeId, bootstrapPolicy());
    }

    /** Policy-injectable variant for tests (so retries don't sleep on real-world delays). */
    void bootstrap(String joinToken, String nodeId, Backoff.Policy policy) throws Exception {
        Backoff.withRetries(
                () -> {
                    sweepOnce(joinToken, nodeId);
                    return null;
                },
                policy);
    }

    /** One pass over all controllers. Returns on first success; throws on permanent or all-transient. */
    private void sweepOnce(String joinToken, String nodeId) throws Exception {
        StatusRuntimeException lastTransient = null;
        for (ControllerEndpoint controller : controllers) {
            try {
                exchange.exchange(controller, joinToken, nodeId);
                return;
            } catch (StatusRuntimeException e) {
                if (isPermanent(e.getStatus())) {
                    // Bad/expired token or unauthorized — other controllers share the same cluster
                    // secret, so trying them won't help. Fail fast (don't retry, don't sweep on).
                    logger.error(
                            "Bootstrap rejected by {} ({}) — not retrying",
                            controller,
                            e.getStatus().getCode());
                    throw e;
                }
                logger.warn(
                        "Bootstrap via {} failed ({}); trying next controller",
                        controller,
                        e.getStatus().getCode());
                lastTransient = e;
            }
        }
        throw new TransientBootstrapException(
                "all " + controllers.size() + " controller(s) unreachable for bootstrap", lastTransient);
    }

    private void grpcExchange(ControllerEndpoint controller, String joinToken, String nodeId) throws Exception {
        logger.info("Bootstrapping node {} with controller {}", nodeId, controller);

        // TLS but trust any server cert (no CA cert yet during bootstrap). The join token authenticates.
        var sslContext = ClientTlsCredentials.buildInsecure();
        // Direct InetSocketAddress: skip the NameResolver registry (shaded jar only registers the
        // unix-socket resolver, which would mangle host:port into a unix path). See DaemonGrpcClient.
        ManagedChannel channel = NettyChannelBuilder.forAddress(
                        new java.net.InetSocketAddress(controller.host(), controller.port()))
                .sslContext(sslContext)
                .build();

        try {
            var stub = BootstrapServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(EXCHANGE_DEADLINE_SECONDS, TimeUnit.SECONDS);
            var response = stub.exchangeJoinToken(ExchangeJoinTokenRequest.newBuilder()
                    .setJoinToken(joinToken)
                    .setNodeId(nodeId)
                    .build());

            Files.createDirectories(certDir);

            Files.write(nodePkcs12Path(), response.getPkcs12().toByteArray());
            FilePermissions.setOwnerReadWrite(nodePkcs12Path());

            Files.writeString(passwordPath(), response.getPkcs12Password());
            FilePermissions.setOwnerReadWrite(passwordPath());

            Files.write(caPemPath(), response.getCaCertificatePem().toByteArray());

            logger.info("Bootstrap successful via {}. Certificate saved to {}", controller, nodePkcs12Path());
        } finally {
            channel.shutdownNow();
        }
    }

    /** Statuses that won't be fixed by retrying or by trying a different controller. */
    private static boolean isPermanent(Status status) {
        return switch (status.getCode()) {
            case INVALID_ARGUMENT, UNAUTHENTICATED, PERMISSION_DENIED -> true;
            default -> false;
        };
    }

    /** The only failures worth retrying: a sweep in which no controller answered. Shared with tests. */
    static final Predicate<Exception> RETRY_TRANSIENT = e -> e instanceof TransientBootstrapException;

    private static Backoff.Policy bootstrapPolicy() {
        // ~1+2+4+8+16s of sleeps across 6 attempts (cap 30s), then give up and let systemd restart.
        return new Backoff.Policy(6, Duration.ofSeconds(1), Duration.ofSeconds(30), 0.25, RETRY_TRANSIENT);
    }

    /** The per-controller enrollment step. Production uses {@link #grpcExchange}; tests inject a stub. */
    @FunctionalInterface
    interface JoinTokenExchange {
        void exchange(ControllerEndpoint controller, String joinToken, String nodeId) throws Exception;
    }

    /** Marker for "no controller was reachable this sweep" — the only failure {@link Backoff} retries. */
    private static final class TransientBootstrapException extends Exception {
        TransientBootstrapException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
