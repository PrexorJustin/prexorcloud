package me.prexorjustin.prexorcloud.controller.grpc;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import io.grpc.*;

/**
 * Server interceptor that extracts the remote peer's IP address from the gRPC
 * transport and stores it in the {@link Context} so service implementations can
 * read it without coupling to transport internals.
 */
public final class PeerAddressInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String address = "";
        SocketAddress remote = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (remote instanceof InetSocketAddress inet) {
            address = inet.getAddress().getHostAddress();
        }

        Context ctx = Context.current().withValue(DaemonServiceImpl.PEER_ADDRESS_KEY, address);
        return Contexts.interceptCall(ctx, call, headers, next);
    }
}
