package me.prexorjustin.prexorcloud.controller.grpc;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.protocol.ProtocolConstants;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server hosting DaemonService, BootstrapService, and AdminService.
 */
public final class GrpcServer {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServer.class);

    private final Server server;

    public GrpcServer(
            String host,
            int port,
            SslContext sslContext,
            DaemonServiceImpl daemonService,
            BootstrapServiceImpl bootstrapService,
            AdminServiceImpl adminService,
            ClusterMembershipServiceImpl clusterMembershipService,
            MtlsEnforcementInterceptor mtlsInterceptor,
            SubnetGuardInterceptor subnetGuardInterceptor) {
        var builder = NettyServerBuilder.forAddress(new InetSocketAddress(host, port))
                .intercept(new CorrelationServerInterceptor())
                .intercept(new PeerAddressInterceptor())
                // Subnet guard runs BEFORE mTLS so we reject disallowed IPs without
                // even paying the cert-verification cost. BootstrapService and
                // ClusterMembership are both exempt from mTLS — they're authenticated
                // by their respective join tokens, not by certs the caller doesn't
                // yet have.
                .intercept(mtlsInterceptor == null ? new MtlsEnforcementInterceptor() : mtlsInterceptor);
        if (subnetGuardInterceptor != null) {
            builder.intercept(subnetGuardInterceptor);
        }
        builder.addService(daemonService)
                .addService(bootstrapService)
                .addService(adminService)
                .maxInboundMessageSize(ProtocolConstants.MAX_MESSAGE_SIZE)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true);
        if (clusterMembershipService != null) {
            builder.addService(clusterMembershipService);
        }

        if (sslContext != null) {
            builder.sslContext(sslContext);
        }

        this.server = builder.build();
    }

    /** Back-compat overload — no subnet guard, no cluster-membership service. */
    public GrpcServer(
            String host,
            int port,
            SslContext sslContext,
            DaemonServiceImpl daemonService,
            BootstrapServiceImpl bootstrapService,
            AdminServiceImpl adminService,
            MtlsEnforcementInterceptor mtlsInterceptor) {
        this(host, port, sslContext, daemonService, bootstrapService, adminService, null, mtlsInterceptor, null);
    }

    /** Back-compat overload — no cluster-membership service. */
    public GrpcServer(
            String host,
            int port,
            SslContext sslContext,
            DaemonServiceImpl daemonService,
            BootstrapServiceImpl bootstrapService,
            AdminServiceImpl adminService,
            MtlsEnforcementInterceptor mtlsInterceptor,
            SubnetGuardInterceptor subnetGuardInterceptor) {
        this(
                host,
                port,
                sslContext,
                daemonService,
                bootstrapService,
                adminService,
                null,
                mtlsInterceptor,
                subnetGuardInterceptor);
    }

    public void start() throws Exception {
        server.start();
        logger.debug("gRPC server listening on port {}", server.getPort());
    }

    public void stop() {
        try {
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            logger.debug("gRPC server stopped");
        } catch (InterruptedException _) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
