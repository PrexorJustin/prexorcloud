package me.prexorjustin.prexorcloud.controller.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.HealingActionIntent;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InstanceLifecycleManagerTest {

    private EventBus eventBus;
    private ClusterState clusterState;
    private WorkflowStateStore workflowStateStore;
    private GroupManager groupManager;
    private Scheduler scheduler;
    private ConsoleBuffer consoleBuffer;
    private InstanceLifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        workflowStateStore = new WorkflowStateStore();
        groupManager = mock(GroupManager.class);
        scheduler = mock(Scheduler.class);
        consoleBuffer = new ConsoleBuffer();
        lifecycleManager = new InstanceLifecycleManager(clusterState, eventBus, groupManager, consoleBuffer);
        lifecycleManager.attachHealingWorkflow(workflowStateStore, scheduler);

        clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));
        when(groupManager.get("lobby")).thenReturn(Optional.of(stubGroup("lobby")));
    }

    @AfterEach
    void tearDown() {
        lifecycleManager.stop();
        eventBus.shutdown();
    }

    @Test
    void crashedInstanceQueuesHealingReplacement() throws InterruptedException {
        clusterState.updateInstanceState("lobby-1", InstanceState.CRASHED);
        Thread.sleep(200);

        var intent = workflowStateStore.getHealingAction("lobby-1");
        assertTrue(intent.isPresent());
        verify(scheduler).scheduleReplacement("lobby", "lobby-1");
    }

    @Test
    void scheduledReplacementClearsHealingIntent() throws InterruptedException {
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-1", "lobby", "INSTANCE_CRASHED", Instant.now()));

        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.SCHEDULED, 25566, 0, 0, Instant.now()));
        Thread.sleep(200);

        assertFalse(workflowStateStore.getHealingAction("lobby-1").isPresent());
    }

    @Test
    void terminalTransitionsEvictConsoleBuffer() throws InterruptedException {
        consoleBuffer.append("lobby-1", "boot line");

        clusterState.updateInstanceState("lobby-1", InstanceState.STOPPED);
        Thread.sleep(200);

        assertTrue(consoleBuffer.getLines("lobby-1").isEmpty());
    }

    @Test
    void reconcileRetriesPersistedHealingActions() {
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-2", "lobby", "INSTANCE_CRASHED", Instant.now()));

        lifecycleManager.reconcilePersistedHealingActions();

        verify(scheduler).scheduleReplacement("lobby", "lobby-2");
    }

    @Test
    void reconcilePersistedHealingActionsSkipsControllerWithoutLease() throws InterruptedException {
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-2", "lobby", "INSTANCE_CRASHED", Instant.now()));

        InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
        var controllerALeaseManager = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
        var controllerBLeaseManager = new DistributedLeaseManager(redis.commands(), "controller-b", 60);
        assertTrue(controllerALeaseManager.tryAcquireLease("healing:lobby-2").isPresent());

        var otherScheduler = mock(Scheduler.class);
        var otherLifecycleManager = new InstanceLifecycleManager(clusterState, eventBus, groupManager, consoleBuffer);
        otherLifecycleManager.attachHealingWorkflow(workflowStateStore, otherScheduler, controllerBLeaseManager);
        try {
            otherLifecycleManager.reconcilePersistedHealingActions();
            Thread.sleep(200);
            verify(otherScheduler, never()).scheduleReplacement("lobby", "lobby-2");

            controllerALeaseManager.release("healing:lobby-2");

            otherLifecycleManager.reconcilePersistedHealingActions();
            Thread.sleep(200);
            verify(otherScheduler, atLeastOnce()).scheduleReplacement("lobby", "lobby-2");
        } finally {
            otherLifecycleManager.stop();
        }
    }

    @Test
    void healingLeaseAcquireTriggersAutomaticReconcile() throws InterruptedException {
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-2", "lobby", "INSTANCE_CRASHED", Instant.now()));

        InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
        var controllerALeaseManager = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
        var controllerBLeaseManager = new DistributedLeaseManager(redis.commands(), "controller-b", 60);
        assertTrue(controllerALeaseManager.tryAcquireLease("healing:lobby-2").isPresent());

        var otherScheduler = mock(Scheduler.class);
        var otherLifecycleManager = new InstanceLifecycleManager(clusterState, eventBus, groupManager, consoleBuffer);
        otherLifecycleManager.attachHealingWorkflow(workflowStateStore, otherScheduler, controllerBLeaseManager);
        try {
            controllerALeaseManager.release("healing:lobby-2");
            assertTrue(
                    controllerBLeaseManager.tryAcquireLease("healing:lobby-2").isPresent());

            Thread.sleep(200);
            verify(otherScheduler).scheduleReplacement("lobby", "lobby-2");
        } finally {
            otherLifecycleManager.stop();
        }
    }

    private GroupConfig stubGroup(String name) {
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
                "",
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
