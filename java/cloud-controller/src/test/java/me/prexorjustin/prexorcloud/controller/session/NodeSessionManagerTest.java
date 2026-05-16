package me.prexorjustin.prexorcloud.controller.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import me.prexorjustin.prexorcloud.protocol.ControllerMessage;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

class NodeSessionManagerTest {

    @Test
    void registerReplacingNodeSessionRemovesOldSessionId() {
        var manager = new NodeSessionManager();
        var first = session("session-1", "node-1");
        var second = session("session-2", "node-1");

        assertTrue(manager.register(first).isEmpty());
        var replaced = manager.register(second);

        assertTrue(replaced.isPresent());
        assertEquals("session-1", replaced.orElseThrow().sessionId());
        assertFalse(manager.getBySessionId("session-1").isPresent());
        assertEquals("session-2", manager.getByNodeId("node-1").orElseThrow().sessionId());
        assertEquals(1, manager.sessionCount());
    }

    @Test
    void invalidatingReplacedSessionKeepsCurrentNodeMapping() {
        var manager = new NodeSessionManager();
        var first = session("session-1", "node-1");
        var second = session("session-2", "node-1");

        manager.register(first);
        manager.register(second);

        assertFalse(manager.invalidate("session-1"));
        assertTrue(manager.getByNodeId("node-1").isPresent());
        assertEquals("session-2", manager.getByNodeId("node-1").orElseThrow().sessionId());
        assertEquals(1, manager.sessionCount());
    }

    private static NodeSession session(String sessionId, String nodeId) {
        return new NodeSession(sessionId, nodeId, new NoOpObserver(), Instant.now());
    }

    private static final class NoOpObserver implements StreamObserver<ControllerMessage> {
        @Override
        public void onNext(ControllerMessage value) {}

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
    }
}
