package me.prexorjustin.prexorcloud.controller.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.api.event.events.NodeDrainCompletedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeDrainRequestedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.NodeDrainIntent;
import me.prexorjustin.prexorcloud.controller.state.NodeState;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("NodeDrainManager")
@ExtendWith(MockitoExtension.class)
class NodeDrainManagerTest {

    private EventBus eventBus;
    private ClusterState clusterState;
    private WorkflowStateStore workflowStateStore;
    private NodeDrainManager drainManager;

    @Mock
    Scheduler scheduler;

    @Mock
    NodeSessionManager sessionManager;

    @Mock
    GroupManager groupManager;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        workflowStateStore = new WorkflowStateStore();
        drainManager = new NodeDrainManager(
                clusterState, workflowStateStore, scheduler, sessionManager, eventBus, groupManager);
    }

    @AfterEach
    void tearDown() {
        drainManager.shutdown();
    }

    private void addNode(String nodeId) {
        clusterState.addNode(nodeId, "10.0.0.1", 4096, Map.of(), Instant.now(), null);
    }

    private void addInstance(String instanceId, String group, String nodeId, InstanceState state) {
        addInstance(instanceId, group, nodeId, state, 0);
    }

    private void addInstance(String instanceId, String group, String nodeId, InstanceState state, int playerCount) {
        clusterState.addInstance(
                new InstanceInfo(instanceId, group, nodeId, state, 25565, playerCount, 0, Instant.now()));
    }

    private void triggerDrain(String nodeId, boolean shutdown) {
        triggerDrain(nodeId, shutdown, 60, "Server shutting down");
    }

    private void triggerDrain(String nodeId, boolean shutdown, int timeout, String kickMessage) {
        clusterState.setNodeStatus(nodeId, NodeState.NodeStatus.DRAINING);
        eventBus.publish(new NodeDrainRequestedEvent(nodeId, shutdown, timeout, kickMessage, Instant.now()));
    }

    private GroupConfig stubGroup(String name) {
        return stubGroup(name, "");
    }

    private GroupConfig stubGroup(String name, String fallbackGroup) {
        return new GroupConfig(
                name,
                null,
                "PAPER",
                "",
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                10,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                true,
                0,
                false,
                List.of(),
                List.of(),
                fallbackGroup,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of(),
                Map.of(),
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    @Nested
    @DisplayName("Drain with no instances")
    class EmptyNode {

        @Test
        @DisplayName("Immediately completes drain and sends ShutdownNode when shutdown=true")
        void completesImmediatelyWithShutdown() throws InterruptedException {
            addNode("node-1");

            var latch = new CountDownLatch(1);
            var captured = new AtomicReference<NodeDrainCompletedEvent>();
            eventBus.subscribe(NodeDrainCompletedEvent.class, e -> {
                captured.set(e);
                latch.countDown();
            });

            var session = mock(NodeSession.class);
            when(sessionManager.getByNodeId("node-1")).thenReturn(Optional.of(session));

            triggerDrain("node-1", true);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals("node-1", captured.get().nodeId());

            var captor = ArgumentCaptor.forClass(ControllerMessage.class);
            verify(session).send(captor.capture());
            assertTrue(captor.getValue().hasShutdownNode());
        }

        @Test
        @DisplayName("Completes drain without ShutdownNode and sets CORDONED when shutdown=false")
        void completesWithoutShutdown() throws InterruptedException {
            addNode("node-1");

            var latch = new CountDownLatch(1);
            eventBus.subscribe(NodeDrainCompletedEvent.class, _ -> latch.countDown());

            triggerDrain("node-1", false);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            verify(sessionManager, never()).getByNodeId(any());
            assertEquals(
                    NodeState.NodeStatus.CORDONED,
                    clusterState.getNode("node-1").orElseThrow().status());
        }
    }

    @Nested
    @DisplayName("Drain with running instances (no players)")
    class WithInstancesNoPlayers {

        @Test
        @DisplayName("Stops all running instances immediately when no players")
        void stopsRunningInstances() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 0);
            addInstance("lobby-2", "lobby", "node-1", InstanceState.RUNNING, 0);

            triggerDrain("node-1", true);
            Thread.sleep(200);

            verify(scheduler).stopInstance("lobby-1", false);
            verify(scheduler).stopInstance("lobby-2", false);
            assertTrue(drainManager.isDraining("node-1"));
        }

        @Test
        @DisplayName("Does not stop already STOPPING instances")
        void skipsStoppingInstances() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 0);
            addInstance("lobby-2", "lobby", "node-1", InstanceState.STOPPING);

            triggerDrain("node-1", true);
            Thread.sleep(200);

            verify(scheduler).stopInstance("lobby-1", false);
            verify(scheduler, never()).stopInstance("lobby-2", false);
        }

        @Test
        @DisplayName("Sends ShutdownNode when shutdown=true and last instance stops")
        void sendsShutdownWhenEnabled() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 0);
            addInstance("lobby-2", "lobby", "node-1", InstanceState.RUNNING, 0);

            var latch = new CountDownLatch(1);
            eventBus.subscribe(NodeDrainCompletedEvent.class, _ -> latch.countDown());

            var session = mock(NodeSession.class);
            when(sessionManager.getByNodeId("node-1")).thenReturn(Optional.of(session));

            triggerDrain("node-1", true);
            Thread.sleep(200);

            clusterState.updateInstanceState("lobby-1", InstanceState.STOPPED);
            Thread.sleep(200);
            assertEquals(1, latch.getCount(), "Should not complete yet");

            clusterState.updateInstanceState("lobby-2", InstanceState.STOPPED);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertFalse(drainManager.isDraining("node-1"));

            var captor = ArgumentCaptor.forClass(ControllerMessage.class);
            verify(session).send(captor.capture());
            assertTrue(captor.getValue().hasShutdownNode());
        }

        @Test
        @DisplayName("Sets CORDONED instead of ShutdownNode when shutdown=false")
        void noShutdownWhenDisabled() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 0);

            var latch = new CountDownLatch(1);
            eventBus.subscribe(NodeDrainCompletedEvent.class, _ -> latch.countDown());

            triggerDrain("node-1", false);
            Thread.sleep(200);

            clusterState.updateInstanceState("lobby-1", InstanceState.STOPPED);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            verify(sessionManager, never()).getByNodeId(any());
            assertEquals(
                    NodeState.NodeStatus.CORDONED,
                    clusterState.getNode("node-1").orElseThrow().status());
        }

        @Test
        @DisplayName("Completes drain when instances CRASHED instead of STOPPED")
        void completesOnCrash() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 0);

            var latch = new CountDownLatch(1);
            eventBus.subscribe(NodeDrainCompletedEvent.class, _ -> latch.countDown());

            triggerDrain("node-1", true);
            Thread.sleep(200);

            clusterState.updateInstanceState("lobby-1", InstanceState.CRASHED);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("Graceful drain with players")
    class WithPlayers {

        @Test
        @DisplayName("Instances with players transition to DRAINING, not STOPPING")
        void drainsInstancesWithPlayers() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 5);

            triggerDrain("node-1", true);
            Thread.sleep(200);

            // Should NOT have been stopped directly
            verify(scheduler, never()).stopInstance("lobby-1", false);
            // Should be in DRAINING state
            assertEquals(
                    InstanceState.DRAINING,
                    clusterState.getInstance("lobby-1").orElseThrow().state());
        }

        @Test
        @DisplayName("Players are queued for transfer to healthy instances")
        void queuesTransfers() throws InterruptedException {
            addNode("node-1");
            addNode("node-2");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 1);
            addInstance("lobby-2", "lobby", "node-2", InstanceState.RUNNING, 0);

            UUID playerUuid = UUID.randomUUID();
            clusterState.addPlayer(playerUuid, "TestPlayer", "lobby-1", "lobby");

            triggerDrain("node-1", true);
            Thread.sleep(200);

            var transfers = workflowStateStore.pendingTransfers();
            assertTrue(transfers.containsKey(playerUuid));
            assertEquals("lobby-2", transfers.get(playerUuid));
        }

        @Test
        @DisplayName("Instance stops after all players leave during drain")
        void stopsAfterPlayersLeave() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 1);

            UUID playerUuid = UUID.randomUUID();
            clusterState.addPlayer(playerUuid, "TestPlayer", "lobby-1", "lobby");

            when(groupManager.get("lobby")).thenReturn(Optional.of(stubGroup("lobby")));

            triggerDrain("node-1", true);
            Thread.sleep(200);

            verify(scheduler, never()).stopInstance("lobby-1", false);

            // Simulate player leaving
            clusterState.removePlayer(playerUuid);
            Thread.sleep(200);

            verify(scheduler).stopInstance("lobby-1", false);
        }

        @Test
        @DisplayName("Remaining players disconnected when timeout reached — server stop handles it")
        void stopsInstancesAfterTimeout() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 1);

            UUID playerUuid = UUID.randomUUID();
            clusterState.addPlayer(playerUuid, "StuckPlayer", "lobby-1", "lobby");

            when(groupManager.get("lobby")).thenReturn(Optional.of(stubGroup("lobby")));

            triggerDrain("node-1", true, 1, "Maintenance window");
            Thread.sleep(1500);

            // Players are not kicked via the queue — server stop disconnects them
            assertTrue(workflowStateStore.pendingTransfers().isEmpty());
        }

        @Test
        @DisplayName("No transfer target — player stays connected until server stops")
        void skipsWhenNoTarget() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 1);
            // No other instances available

            UUID playerUuid = UUID.randomUUID();
            clusterState.addPlayer(playerUuid, "TestPlayer", "lobby-1", "lobby");

            when(groupManager.get("lobby")).thenReturn(Optional.of(stubGroup("lobby")));

            triggerDrain("node-1", true, 60, "Server shutting down");
            Thread.sleep(200);

            // No kick queued — player stays until server stops
            assertTrue(workflowStateStore.pendingTransfers().isEmpty());
        }

        @Test
        @DisplayName("Uses fallback group when same group has no targets")
        void usesFallbackGroup() throws InterruptedException {
            addNode("node-1");
            addNode("node-2");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 1);
            addInstance("hub-1", "hub", "node-2", InstanceState.RUNNING, 0);

            UUID playerUuid = UUID.randomUUID();
            clusterState.addPlayer(playerUuid, "TestPlayer", "lobby-1", "lobby");

            when(groupManager.get("lobby")).thenReturn(Optional.of(stubGroup("lobby", "hub")));

            triggerDrain("node-1", true);
            Thread.sleep(200);

            var transfers = workflowStateStore.pendingTransfers();
            assertTrue(transfers.containsKey(playerUuid));
            assertEquals("hub-1", transfers.get(playerUuid));
        }

        @Test
        @DisplayName("Reconciles persisted drains on startup")
        void reconcilesPersistedDrains() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.DRAINING, 0);
            workflowStateStore.saveNodeDrain(new NodeDrainIntent(
                    "node-1",
                    true,
                    "Maintenance",
                    Instant.now(),
                    Instant.now().plusSeconds(30),
                    java.util.Set.of("lobby-1")));

            drainManager.reconcilePersistedDrains();
            Thread.sleep(200);

            verify(scheduler).stopInstance("lobby-1", false);
        }

        @Test
        @DisplayName("Reconciles persisted drains only after this controller acquires the drain lease")
        void reconcilesPersistedDrainsAfterLeaseHandoff() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.DRAINING, 0);
            workflowStateStore.saveNodeDrain(new NodeDrainIntent(
                    "node-1",
                    true,
                    "Maintenance",
                    Instant.now(),
                    Instant.now().plusSeconds(30),
                    java.util.Set.of("lobby-1")));

            InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
            var controllerALeaseManager = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
            var controllerBLeaseManager = new DistributedLeaseManager(redis.commands(), "controller-b", 60);
            assertTrue(
                    controllerALeaseManager.tryAcquireLease("node-drain:node-1").isPresent());

            var otherScheduler = mock(Scheduler.class);
            var leasedDrainManager = new NodeDrainManager(
                    clusterState,
                    workflowStateStore,
                    otherScheduler,
                    sessionManager,
                    eventBus,
                    groupManager,
                    controllerBLeaseManager);
            try {
                leasedDrainManager.reconcilePersistedDrains();
                Thread.sleep(200);
                verify(otherScheduler, never()).stopInstance("lobby-1", false);

                controllerALeaseManager.release("node-drain:node-1");

                leasedDrainManager.reconcilePersistedDrains();
                Thread.sleep(200);
                verify(otherScheduler, atLeastOnce()).stopInstance("lobby-1", false);
            } finally {
                leasedDrainManager.shutdown();
            }
        }

        @Test
        @DisplayName("Lease acquisition callback immediately reconciles persisted drains after handoff")
        void leaseAcquireTriggersAutomaticDrainReconcile() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.DRAINING, 0);
            workflowStateStore.saveNodeDrain(new NodeDrainIntent(
                    "node-1",
                    true,
                    "Maintenance",
                    Instant.now(),
                    Instant.now().plusSeconds(30),
                    java.util.Set.of("lobby-1")));

            InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
            var controllerALeaseManager = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
            var controllerBLeaseManager = new DistributedLeaseManager(redis.commands(), "controller-b", 60);
            assertTrue(
                    controllerALeaseManager.tryAcquireLease("node-drain:node-1").isPresent());

            var otherScheduler = mock(Scheduler.class);
            var leasedDrainManager = new NodeDrainManager(
                    clusterState,
                    workflowStateStore,
                    otherScheduler,
                    sessionManager,
                    eventBus,
                    groupManager,
                    controllerBLeaseManager);
            try {
                controllerALeaseManager.release("node-drain:node-1");
                assertTrue(controllerBLeaseManager
                        .tryAcquireLease("node-drain:node-1")
                        .isPresent());

                Thread.sleep(200);
                verify(otherScheduler).stopInstance("lobby-1", false);
            } finally {
                leasedDrainManager.shutdown();
            }
        }

        @Test
        @DisplayName("Mixed instances: players drain, empty stop immediately")
        void mixedInstances() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 3);
            addInstance("lobby-2", "lobby", "node-1", InstanceState.RUNNING, 0);

            triggerDrain("node-1", true);
            Thread.sleep(200);

            // Empty instance stopped immediately
            verify(scheduler).stopInstance("lobby-2", false);
            // Instance with players should be draining, not stopped
            verify(scheduler, never()).stopInstance("lobby-1", false);
            assertEquals(
                    InstanceState.DRAINING,
                    clusterState.getInstance("lobby-1").orElseThrow().state());
        }
    }

    @Nested
    @DisplayName("Ignores non-draining nodes")
    class NonDrainingNodes {

        @Test
        @DisplayName("Instance state change on non-draining node is ignored")
        void ignoresNonDrainingNode() throws InterruptedException {
            addNode("node-1");
            addInstance("lobby-1", "lobby", "node-1", InstanceState.RUNNING);

            var called = new CountDownLatch(1);
            eventBus.subscribe(NodeDrainCompletedEvent.class, _ -> called.countDown());

            clusterState.updateInstanceState("lobby-1", InstanceState.STOPPED);
            Thread.sleep(300);

            assertEquals(1, called.getCount(), "Should not have completed drain");
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
