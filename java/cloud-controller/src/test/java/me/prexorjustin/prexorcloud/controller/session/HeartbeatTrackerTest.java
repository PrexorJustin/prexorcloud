package me.prexorjustin.prexorcloud.controller.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.event.events.NodeHeartbeatResumedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeHeartbeatStaleEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.NodeHostInfo;
import me.prexorjustin.prexorcloud.controller.state.NodeState;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HeartbeatTracker — stale / resumed event emission")
class HeartbeatTrackerTest {

    private EventBus eventBus;
    private ClusterState clusterState;
    private NodeSessionManager sessionManager;
    private HeartbeatTracker tracker;
    private CopyOnWriteArrayList<NodeHeartbeatStaleEvent> stale;
    private CopyOnWriteArrayList<NodeHeartbeatResumedEvent> resumed;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        sessionManager = new NodeSessionManager();
        tracker = new HeartbeatTracker(sessionManager, clusterState, eventBus, 3);
        stale = new CopyOnWriteArrayList<>();
        resumed = new CopyOnWriteArrayList<>();
        eventBus.subscribe(NodeHeartbeatStaleEvent.class, stale::add);
        eventBus.subscribe(NodeHeartbeatResumedEvent.class, resumed::add);

        clusterState.addNode("node-1", "10.0.0.1:5000", 8192L, Map.of(), Instant.now(), NodeHostInfo.UNKNOWN);
        sessionManager.register(new NodeSession("session-1", "node-1", new NoOpObserver(), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    @DisplayName("threshold of missed pings emits STALE once and marks node UNREACHABLE")
    void emitsStaleOnceAtThreshold() throws Exception {
        tracker.pingAll();
        tracker.pingAll();
        tracker.pingAll();

        waitForStale(1);
        assertEquals(1, stale.size());
        var ev = stale.get(0);
        assertEquals("node-1", ev.nodeId());
        assertEquals(3, ev.missedPongs());
        assertNotNull(ev.lastHeartbeatAt());

        tracker.pingAll();
        Thread.sleep(120);
        assertEquals(1, stale.size(), "STALE must not re-emit while session is already stale");

        assertEquals(
                NodeState.NodeStatus.UNREACHABLE,
                clusterState.getNode("node-1").orElseThrow().status());
    }

    @Test
    @DisplayName("pong after stale emits RESUMED and flips UNREACHABLE → ONLINE")
    void resumedAfterStale() throws Exception {
        tracker.pingAll();
        tracker.pingAll();
        tracker.pingAll();
        waitForStale(1);

        tracker.recordPong("session-1");
        waitForResumed(1);

        var ev = resumed.get(0);
        assertEquals("node-1", ev.nodeId());
        assertEquals(
                NodeState.NodeStatus.ONLINE,
                clusterState.getNode("node-1").orElseThrow().status());
    }

    @Test
    @DisplayName("pong without prior stale does not emit RESUMED")
    void quietPongIsSilent() throws Exception {
        tracker.pingAll();
        tracker.recordPong("session-1");
        Thread.sleep(120);
        assertEquals(0, stale.size());
        assertEquals(0, resumed.size());
    }

    @Test
    @DisplayName("DRAINING node returns to DRAINING after RESUMED, not ONLINE")
    void resumePreservesNonUnreachableStatus() throws Exception {
        tracker.pingAll();
        tracker.pingAll();
        tracker.pingAll();
        waitForStale(1);
        // Operator transitions to CORDONED while node is unreachable.
        clusterState.setNodeStatus("node-1", NodeState.NodeStatus.CORDONED);

        tracker.recordPong("session-1");
        waitForResumed(1);

        assertEquals(
                NodeState.NodeStatus.CORDONED,
                clusterState.getNode("node-1").orElseThrow().status(),
                "operator status must not be overridden on resume");
    }

    private void waitForStale(int min) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (stale.size() < min && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(stale.size() >= min, "expected " + min + " stale events, got " + stale.size());
    }

    private void waitForResumed(int min) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (resumed.size() < min && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(resumed.size() >= min, "expected " + min + " resumed events, got " + resumed.size());
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
