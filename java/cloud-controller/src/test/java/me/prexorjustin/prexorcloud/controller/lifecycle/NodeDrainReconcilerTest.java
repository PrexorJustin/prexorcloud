package me.prexorjustin.prexorcloud.controller.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.event.events.NodeDrainCompletedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.NodeDrainIntent;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeDrainReconcilerTest {

    private EventBus eventBus;
    private ClusterState clusterState;
    private WorkflowStateStore workflowStateStore;
    private Scheduler scheduler;
    private NodeSessionManager sessionManager;
    private RecordingTimeoutController timeoutController;
    private NodeDrainReconciler drainReconciler;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        workflowStateStore = new WorkflowStateStore();
        scheduler = mock(Scheduler.class);
        sessionManager = mock(NodeSessionManager.class);
        timeoutController = new RecordingTimeoutController();
        drainReconciler = new NodeDrainReconciler(
                clusterState, workflowStateStore, scheduler, sessionManager, eventBus, timeoutController);
    }

    @Test
    void reconcileStopsPlayerlessInstancesAndSchedulesTimeout() {
        clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.DRAINING, 25565, 0, 0, Instant.now()));
        workflowStateStore.saveNodeDrain(new NodeDrainIntent(
                "node-1", true, "Maintenance", Instant.now(), Instant.now().plusSeconds(30), Set.of("lobby-1")));

        drainReconciler.reconcilePersistedDrains();

        verify(scheduler).stopInstance("lobby-1", false);
        assertTrue(timeoutController.scheduledNodes.containsKey("node-1"));
    }

    @Test
    void checkDrainCompletionPublishesCompletionAndSendsShutdown() throws InterruptedException {
        clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.STOPPED, 25565, 0, 0, Instant.now()));
        workflowStateStore.saveNodeDrain(new NodeDrainIntent(
                "node-1", true, "Maintenance", Instant.now(), Instant.now().plusSeconds(30), Set.of()));

        var completed = new CountDownLatch(1);
        eventBus.subscribe(NodeDrainCompletedEvent.class, event -> completed.countDown());
        var session = mock(NodeSession.class);
        when(sessionManager.getByNodeId("node-1")).thenReturn(Optional.of(session));

        drainReconciler.checkDrainCompletion("node-1");

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertFalse(workflowStateStore.getNodeDrain("node-1").isPresent());
        verify(session).send(org.mockito.ArgumentMatchers.any());
        assertTrue(timeoutController.cancelledNodes.contains("node-1"));
    }

    private static final class RecordingTimeoutController implements NodeDrainReconciler.TimeoutController {

        private final Map<String, Instant> scheduledNodes = new ConcurrentHashMap<>();
        private final Set<String> cancelledNodes = ConcurrentHashMap.newKeySet();

        @Override
        public void schedule(String nodeId, Instant timeoutAt) {
            scheduledNodes.put(nodeId, timeoutAt);
        }

        @Override
        public void cancel(String nodeId) {
            cancelledNodes.add(nodeId);
            scheduledNodes.remove(nodeId);
        }
    }
}
