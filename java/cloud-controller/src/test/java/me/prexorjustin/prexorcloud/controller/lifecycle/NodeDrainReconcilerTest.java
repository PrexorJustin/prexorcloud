package me.prexorjustin.prexorcloud.controller.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.event.events.NodeDrainCompletedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.NodeDrainIntent;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
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
                clusterState, workflowStateStore, scheduler, sessionManager, eventBus, null, timeoutController);
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

    @Test
    void leaseAwareReconcileWaitsForLeaseHandoff() {
        clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.DRAINING, 25565, 0, 0, Instant.now()));
        workflowStateStore.saveNodeDrain(new NodeDrainIntent(
                "node-1", true, "Maintenance", Instant.now(), Instant.now().plusSeconds(30), Set.of("lobby-1")));

        InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
        var controllerALeaseManager = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
        var controllerBLeaseManager = new DistributedLeaseManager(redis.commands(), "controller-b", 60);
        assertTrue(controllerALeaseManager.tryAcquireLease("node-drain:node-1").isPresent());

        var leaseAwareTimeouts = new RecordingTimeoutController();
        var otherScheduler = mock(Scheduler.class);
        var leaseAwareReconciler = new NodeDrainReconciler(
                clusterState,
                workflowStateStore,
                otherScheduler,
                sessionManager,
                eventBus,
                controllerBLeaseManager,
                leaseAwareTimeouts);

        leaseAwareReconciler.reconcilePersistedDrains();
        verify(otherScheduler, never()).stopInstance("lobby-1", false);

        controllerALeaseManager.release("node-drain:node-1");

        leaseAwareReconciler.reconcilePersistedDrains();
        verify(otherScheduler).stopInstance("lobby-1", false);
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

    private static final class InMemoryLeaseRedis {

        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, Long> counters = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        private RedisCommands<String, String> commands() {
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                    RedisCommands.class.getClassLoader(),
                    new Class<?>[] {RedisCommands.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> set((String) args[0], (String) args[1], (SetArgs) args[2]);
                        case "get" -> values.get((String) args[0]);
                        case "expire" -> true;
                        case "del" -> deleteAll(args);
                        case "incr" -> counters.merge((String) args[0], 1L, Long::sum);
                        case "scan" -> scan();
                        case "toString" -> "InMemoryLeaseRedis";
                        default ->
                            throw new UnsupportedOperationException("Unsupported Redis method: " + method.getName());
                    });
        }

        private String set(String key, String value, SetArgs args) {
            if (values.containsKey(key)) {
                return null;
            }
            values.put(key, value);
            return "OK";
        }

        private long deleteAll(Object[] args) {
            long deleted = 0;
            for (Object rawKey : args) {
                if (rawKey instanceof String key) {
                    deleted += values.remove(key) != null ? 1 : 0;
                } else if (rawKey instanceof String[] keys) {
                    for (String key : keys) {
                        deleted += values.remove(key) != null ? 1 : 0;
                    }
                }
            }
            return deleted;
        }

        private KeyScanCursor<String> scan() {
            KeyScanCursor<String> cursor = new KeyScanCursor<>();
            cursor.setCursor(ScanCursor.FINISHED.getCursor());
            cursor.setFinished(true);
            cursor.getKeys().addAll(values.keySet());
            return cursor;
        }
    }
}
