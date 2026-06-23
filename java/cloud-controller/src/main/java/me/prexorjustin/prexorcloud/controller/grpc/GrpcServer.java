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
            MtlsEnforcementInterceptor mtlsInterceptor,
            SubnetGuardInterceptor subnetGuardInterceptor) {
        var builder = NettyServerBuilder.forAddress(new InetSocketAddress(host, port))
                .intercept(new CorrelationServerInterceptor())
                .intercept(new PeerAddressInterceptor())
                // Subnet guard runs BEFORE mTLS so we reject disallowed IPs without
                // even paying the cert-verification cost. BootstrapService is exempt
                // from mTLS — it is authenticated by the join token, not by a cert the
                // caller does not yet have.
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
                .permitKeepAliveWithoutCalls(true)
                // The daemon pings every 30s. Without an explicit floor the server's default minimum is
                // 5 min, so it would send GOAWAY "too_many_pings" and kill the connection — today only
                // averted because the daemon also sends data frames often enough to reset the counter.
                // Permit pings as frequent as 15s so a quiet node (0 instances, ping-only) is never culled.
                .permitKeepAliveTime(15, TimeUnit.SECONDS);

        if (sslContext != null) {
            builder.sslContext(sslContext);
        }

        this.server = builder.build();
    }

    /** Back-compat overload — no subnet guard. */
    public GrpcServer(
            String host,
            int port,
            SslContext sslContext,
            DaemonServiceImpl daemonService,
            BootstrapServiceImpl bootstrapService,
            AdminServiceImpl adminService,
            MtlsEnforcementInterceptor mtlsInterceptor) {
        this(host, port, sslContext, daemonService, bootstrapService, adminService, mtlsInterceptor, null);
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
