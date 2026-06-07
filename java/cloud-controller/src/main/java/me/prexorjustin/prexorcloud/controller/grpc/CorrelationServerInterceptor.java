package me.prexorjustin.prexorcloud.controller.grpc;

import java.util.Map;

import me.prexorjustin.prexorcloud.common.logging.CorrelationContext;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Adds correlation and method fields to MDC for every gRPC callback.
 */
public final class CorrelationServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> CORRELATION_HEADER =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String correlationId = CorrelationContext.sanitize(headers.get(CORRELATION_HEADER));
        String grpcMethod = call.getMethodDescriptor().getFullMethodName();
        Map<String, String> fields = Map.of(CorrelationContext.CORRELATION_ID, correlationId, "grpcMethod", grpcMethod);

        ServerCall.Listener<ReqT> listener;
        try (var ignored = CorrelationContext.open(fields)) {
            listener = next.startCall(call, headers);
        }

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onMessage(ReqT message) {
                try (var ignored = CorrelationContext.open(fields)) {
                    super.onMessage(message);
                }
            }

            @Override
            public void onHalfClose() {
                try (var ignored = CorrelationContext.open(fields)) {
                    super.onHalfClose();
                }
            }

            @Override
            public void onCancel() {
                try (var ignored = CorrelationContext.open(fields)) {
                    super.onCancel();
                }
            }

            @Override
            public void onComplete() {
                try (var ignored = CorrelationContext.open(fields)) {
                    super.onComplete();
                }
            }

            @Override
            public void onReady() {
                try (var ignored = CorrelationContext.open(fields)) {
                    super.onReady();
                }
            }
        };
    }
}
