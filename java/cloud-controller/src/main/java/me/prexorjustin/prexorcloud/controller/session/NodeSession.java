package me.prexorjustin.prexorcloud.controller.session;

import java.time.Instant;

import me.prexorjustin.prexorcloud.protocol.ControllerMessage;

import io.grpc.stub.StreamObserver;

public record NodeSession(
        String sessionId, String nodeId, StreamObserver<ControllerMessage> responseStream, Instant connectedAt) {

    public void send(ControllerMessage message) {
        responseStream.onNext(message);
    }
}
