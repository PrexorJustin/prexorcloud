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

    @Test
    void disconnectAllTerminatesEveryStream() {
        var manager = new NodeSessionManager();
        var obs1 = new RecordingObserver();
        var obs2 = new RecordingObserver();
        manager.register(new NodeSession("session-1", "node-1", obs1, Instant.now()));
        manager.register(new NodeSession("session-2", "node-2", obs2, Instant.now()));

        int closed = manager.disconnectAll("leadership lost");

        assertEquals(2, closed);
        assertTrue(obs1.errored);
        assertTrue(obs2.errored);
        // UNAVAILABLE is the status the daemon's reconnect path handles.
        assertEquals(
                io.grpc.Status.Code.UNAVAILABLE,
                io.grpc.Status.fromThrowable(obs1.error).getCode());
    }

    @Test
    void disconnectAllOnEmptyManagerIsNoOp() {
        assertEquals(0, new NodeSessionManager().disconnectAll("leadership lost"));
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

    private static final class RecordingObserver implements StreamObserver<ControllerMessage> {
        volatile boolean errored;
        volatile Throwable error;

        @Override
        public void onNext(ControllerMessage value) {}

        @Override
        public void onError(Throwable t) {
            errored = true;
            error = t;
        }

        @Override
        public void onCompleted() {}
    }
}
