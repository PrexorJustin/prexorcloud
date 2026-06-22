package me.prexorjustin.prexorcloud.controller.session;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.observability.telemetry.TraceContextWire;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;

import io.grpc.Status;
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
        // gRPC StreamObserver is not thread-safe: concurrent onNext on one stream interleaves and
        // corrupts the outbound frame ("Failed to frame message" / freed ByteBuf). Placement and
        // recovery dispatch sends from multiple threads (parallel placement, virtual threads), so
        // serialize per stream. This send() is the single outbound chokepoint, so one monitor per
        // session covers every write.
        synchronized (responseStream) {
            responseStream.onNext(message);
        }
    }

    /**
     * Forcibly terminate this stream so the daemon treats it as a disconnect, reconnects, and
     * re-handshakes. Used on leadership loss: a daemon still attached to this (now-demoted)
     * controller must be steered to the new leader, but a healthy stream never re-handshakes on its
     * own — so we break it. {@code UNAVAILABLE} is the status the daemon's reconnect path already
     * handles. Synchronized on the same monitor as {@link #send} so it never races a concurrent write.
     */
    public void disconnect(String reason) {
        synchronized (responseStream) {
            try {
                responseStream.onError(
                        Status.UNAVAILABLE.withDescription(reason).asRuntimeException());
            } catch (RuntimeException alreadyTerminated) {
                // Stream already closed (daemon gone / concurrent teardown) — nothing to do.
            }
        }
    }
}
