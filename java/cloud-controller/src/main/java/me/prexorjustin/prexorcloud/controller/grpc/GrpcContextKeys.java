package me.prexorjustin.prexorcloud.controller.grpc;

import javax.net.ssl.SSLSession;

import io.grpc.Context;

/**
 * gRPC context keys shared between interceptors and service implementations.
 */
public final class GrpcContextKeys {

    private GrpcContextKeys() {}

    /**
     * The SSL session from the client connection, propagated by
     * {@link MtlsEnforcementInterceptor}.
     */
    public static final Context.Key<SSLSession> SSL_SESSION_KEY = Context.key("ssl-session");
}
