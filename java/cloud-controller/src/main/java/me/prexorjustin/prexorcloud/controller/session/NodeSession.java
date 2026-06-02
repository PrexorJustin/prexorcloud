package me.prexorjustin.prexorcloud.controller.session;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.observability.telemetry.TraceContextWire;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;

import io.grpc.stub.StreamObserver;

public record NodeSession(
        String sessionId, String nodeId, StreamObserver<ControllerMessage> responseStream, Instant connectedAt) {

    /**
     * Send a controller→daemon message, stamping the current W3C {@code traceparent} so the daemon
     * can continue the trace (Track D.3). This is the single outbound chokepoint, so any message
     * sent while a span is active (e.g. {@code placement.dispatch}, a traced REST request) carries
     * the context; when telemetry is off or no span is active nothing is stamped.
     */
    public void send(ControllerMessage message) {
        String traceparent = TraceContextWire.currentTraceparent();
        if (!traceparent.isEmpty() && message.getTraceparent().isEmpty()) {
            message = message.toBuilder().setTraceparent(traceparent).build();
        }
        responseStream.onNext(message);
    }
}
