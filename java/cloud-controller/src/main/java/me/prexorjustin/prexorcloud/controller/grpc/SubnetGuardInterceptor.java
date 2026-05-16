package me.prexorjustin.prexorcloud.controller.grpc;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.rest.middleware.AllowedSubnetsList;

import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC counterpart of {@link me.prexorjustin.prexorcloud.controller.rest.middleware.SubnetGuardMiddleware}.
 * Rejects calls whose remote socket address isn't in the controller's
 * {@link AllowedSubnetsList}.
 * <p>
 * BootstrapService is exempt — the join token is the auth for that endpoint
 * and the exchange itself auto-registers the daemon's source IP so subsequent
 * DaemonService calls pass the guard.
 */
public final class SubnetGuardInterceptor implements ServerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(SubnetGuardInterceptor.class);

    private static final Set<String> EXEMPT_SERVICES = Set.of("me.prexorjustin.prexorcloud.protocol.BootstrapService");

    private final AllowedSubnetsList allowList;

    public SubnetGuardInterceptor(AllowedSubnetsList allowList) {
        this.allowList = allowList;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String serviceName = call.getMethodDescriptor().getServiceName();
        if (serviceName != null && EXEMPT_SERVICES.contains(serviceName)) {
            return next.startCall(call, headers);
        }

        SocketAddress remote = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (!(remote instanceof InetSocketAddress inet)) {
            // Unix domain sockets / unexpected transports — treat as deny.
            logger.warn(
                    "Rejected {} — remote address unavailable or not Inet",
                    call.getMethodDescriptor().getFullMethodName());
            call.close(
                    Status.PERMISSION_DENIED.withDescription("Source address could not be determined"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        InetAddress source = inet.getAddress();
        if (!allowList.allows(source)) {
            logger.warn(
                    "Rejected {} from {} — not in allowedSubnets",
                    call.getMethodDescriptor().getFullMethodName(),
                    source.getHostAddress());
            call.close(
                    Status.PERMISSION_DENIED.withDescription(
                            "Source IP " + source.getHostAddress() + " is not in the controller's allowedSubnets list"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
