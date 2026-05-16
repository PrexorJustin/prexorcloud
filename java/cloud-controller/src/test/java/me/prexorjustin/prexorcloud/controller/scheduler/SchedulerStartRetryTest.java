package me.prexorjustin.prexorcloud.controller.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentReconciler;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.InstanceCompositionPlan;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.InstanceCompositionPlanner;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.StartRetryIntent;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import io.grpc.stub.StreamObserver;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchedulerStartRetryTest {

    private EventBus eventBus;
    private ClusterState clusterState;
    private NodeSessionManager sessionManager;
    private WorkflowStateStore workflowStateStore;
    private StateStore stateStore;
    private GroupManager groupManager;
    private ScalingEvaluator scalingEvaluator;
    private NodeMessageDispatcher nodeMessageDispatcher;
    private InstancePlacementCoordinator placementCoordinator;
    private DeploymentReconciler deploymentReconciler;
    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        sessionManager = new NodeSessionManager();
        workflowStateStore = new WorkflowStateStore();
        stateStore = mock(StateStore.class);
        groupManager = mock(GroupManager.class);
        scalingEvaluator = mock(ScalingEvaluator.class);
        nodeMessageDispatcher = new NodeMessageDispatcher(sessionManager, null, null);
        placementCoordinator = new InstancePlacementCoordinator(
                clusterState,
                mock(NodeSelector.class),
                scalingEvaluator,
                stateStore,
                mock(InstanceCompositionPlanner.class),
                nodeMessageDispatcher,
                "http://localhost:8080");
        deploymentReconciler =
                new DeploymentReconciler(clusterState, stateStore, eventBus, 30, (instanceId, force) -> true);

        scheduler = new Scheduler(
                groupManager,
                clusterState,
                scalingEvaluator,
                mock(CrashLoopDetector.class),
                stateStore,
                workflowStateStore,
                placementCoordinator,
                deploymentReconciler,
                30,
                () -> false,
                null,
                null,
                nodeMessageDispatcher);
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
        eventBus.shutdown();
    }

    @Test
    void reconcilePersistedStartRetriesResendsQueuedStart() throws Exception {
        var messages = new CopyOnWriteArrayList<ControllerMessage>();
        var sent = new CountDownLatch(1);
        sessionManager.register(new NodeSession(
                "session-1",
                "node-1",
                new StreamObserver<>() {
                    @Override
                    public void onNext(ControllerMessage value) {
                        messages.add(value);
                        sent.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {}
                },
                Instant.now()));

        var group = stubGroup("lobby");
        when(groupManager.get("lobby")).thenReturn(Optional.of(group));
        when(groupManager.resolveGroup("lobby")).thenReturn(group);

        clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.SCHEDULED, 25565, 0, 0, Instant.now()));

        var compositionPlan = compositionPlan("lobby-1");
        when(stateStore.getInstanceCompositionPlan("lobby-1")).thenReturn(Optional.of(compositionPlan));

        workflowStateStore.saveStartRetry(new StartRetryIntent(
                "lobby-1",
                "lobby",
                "node-1",
                "RUNTIME_PROVISION_FAILED",
                compositionPlan.planHash(),
                2,
                Instant.now(),
                Instant.now().minusSeconds(5)));

        scheduler.reconcilePersistedStartRetries();

        assertTrue(sent.await(5, TimeUnit.SECONDS));
        assertFalse(messages.isEmpty());
        var start = messages.getFirst().getStartInstance();
        assertEquals("lobby-1", start.getInstanceId());
        assertEquals(compositionPlan.planHash(), start.getCompositionPlan().getPlanHash());
        assertFalse(start.getPluginToken().isBlank());
        assertTrue(workflowStateStore.getStartRetry("lobby-1").isPresent());
    }

    @Test
    void reconcileRecoverableStartsResendsPersistedScheduledInstance() throws Exception {
        var messages = new CopyOnWriteArrayList<ControllerMessage>();
        var sent = new CountDownLatch(1);
        sessionManager.register(new NodeSession(
                "session-recover",
                "node-1",
                new StreamObserver<>() {
                    @Override
                    public void onNext(ControllerMessage value) {
                        messages.add(value);
                        sent.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {}
                },
                Instant.now()));

        var group = stubGroup("lobby");
        when(groupManager.get("lobby")).thenReturn(Optional.of(group));
        when(groupManager.resolveGroup("lobby")).thenReturn(group);

        clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
        clusterState.addInstance(new InstanceInfo(
                "lobby-recover", "lobby", "node-1", InstanceState.SCHEDULED, 25571, 0, 0, Instant.now()));
        var compositionPlan = compositionPlan("lobby-recover");
        when(stateStore.getInstanceCompositionPlan("lobby-recover")).thenReturn(Optional.of(compositionPlan));

        scheduler.reconcileRecoverableStarts();

        assertTrue(sent.await(5, TimeUnit.SECONDS));
        assertEquals("lobby-recover", messages.getFirst().getStartInstance().getInstanceId());
        assertEquals(
                compositionPlan.planHash(),
                messages.getFirst().getStartInstance().getCompositionPlan().getPlanHash());
    }

    @Test
    void reconcileRecoverableStartsForNodeTargetsOnlyMatchingNode() throws Exception {
        var messages = new CopyOnWriteArrayList<ControllerMessage>();
        var sent = new CountDownLatch(1);
        sessionManager.register(new NodeSession(
                "session-node-target",
                "node-1",
                new StreamObserver<>() {
                    @Override
                    public void onNext(ControllerMessage value) {
                        messages.add(value);
                        sent.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {}
                },
                Instant.now()));

        var group = stubGroup("lobby");
        when(groupManager.get("lobby")).thenReturn(Optional.of(group));
        when(groupManager.resolveGroup("lobby")).thenReturn(group);

        clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
        clusterState.addNode("node-2", "10.0.0.2", 4096, Map.of(), Instant.now(), null);
        clusterState.addInstance(new InstanceInfo(
                "lobby-node-1", "lobby", "node-1", InstanceState.SCHEDULED, 25573, 0, 0, Instant.now()));
        clusterState.addInstance(new InstanceInfo(
                "lobby-node-2", "lobby", "node-2", InstanceState.SCHEDULED, 25574, 0, 0, Instant.now()));
        when(stateStore.getInstanceCompositionPlan("lobby-node-1"))
                .thenReturn(Optional.of(compositionPlan("lobby-node-1")));
        when(stateStore.getInstanceCompositionPlan("lobby-node-2"))
                .thenReturn(Optional.of(compositionPlan("lobby-node-2")));

        scheduler.reconcileRecoverableStartsForNode("node-1");

        assertTrue(sent.await(5, TimeUnit.SECONDS));
        assertEquals(1, messages.size());
        assertEquals("lobby-node-1", messages.getFirst().getStartInstance().getInstanceId());
    }

    @Test
    void leaseAcquisitionTriggersRecoverableStartReconciliation() throws Exception {
        InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
        var leaseManager = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
        var leasedScheduler = new Scheduler(
                groupManager,
                clusterState,
                scalingEvaluator,
                mock(CrashLoopDetector.class),
                stateStore,
                workflowStateStore,
                placementCoordinator,
                deploymentReconciler,
                30,
                () -> false,
                leaseManager,
                null,
                nodeMessageDispatcher);
        try {
            var messages = new CopyOnWriteArrayList<ControllerMessage>();
            var sent = new CountDownLatch(1);
            sessionManager.register(new NodeSession(
                    "session-recover-handoff",
                    "node-1",
                    new StreamObserver<>() {
                        @Override
                        public void onNext(ControllerMessage value) {
                            messages.add(value);
                            sent.countDown();
                        }

                        @Override
                        public void onError(Throwable t) {}

                        @Override
                        public void onCompleted() {}
                    },
                    Instant.now()));

            var group = stubGroup("lobby");
            when(groupManager.get("lobby")).thenReturn(Optional.of(group));
            when(groupManager.resolveGroup("lobby")).thenReturn(group);
            clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
            clusterState.addInstance(new InstanceInfo(
                    "lobby-recover-handoff", "lobby", "node-1", InstanceState.SCHEDULED, 25572, 0, 0, Instant.now()));
            var compositionPlan = compositionPlan("lobby-recover-handoff");
            when(stateStore.getInstanceCompositionPlan("lobby-recover-handoff"))
                    .thenReturn(Optional.of(compositionPlan));

            leaseManager.tryAcquireLease("group:lobby").orElseThrow();

            assertTrue(sent.await(5, TimeUnit.SECONDS));
            assertEquals(
                    "lobby-recover-handoff",
                    messages.getFirst().getStartInstance().getInstanceId());
        } finally {
            leasedScheduler.stop();
        }
    }

    @Test
    void reconcilePersistedStartRetriesClearsTerminalRetries() {
        clusterState.addInstance(
                new InstanceInfo("lobby-2", "lobby", "node-1", InstanceState.CRASHED, 25566, 0, 0, Instant.now()));
        workflowStateStore.saveStartRetry(new StartRetryIntent(
                "lobby-2", "lobby", "node-1", "RUNTIME_PROVISION_FAILED", "plan-2", 1, Instant.now(), Instant.now()));

        scheduler.reconcilePersistedStartRetries();

        assertFalse(workflowStateStore.getStartRetry("lobby-2").isPresent());
    }

    @Test
    void reconcilePersistedStartRetriesSkipsControllerWithoutLease() throws Exception {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.incr(RedisKeys.leaseToken("group:lobby"))).thenReturn(1L);
        when(commands.set(
                        org.mockito.ArgumentMatchers.eq(RedisKeys.lease("group:lobby")),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(SetArgs.class)))
                .thenReturn(null);
        when(commands.get(RedisKeys.lease("group:lobby"))).thenReturn("controller-b|2");

        var leasedScheduler = new Scheduler(
                groupManager,
                clusterState,
                scalingEvaluator,
                mock(CrashLoopDetector.class),
                stateStore,
                workflowStateStore,
                placementCoordinator,
                deploymentReconciler,
                30,
                () -> false,
                new DistributedLeaseManager(commands, "controller-a", 60),
                null,
                nodeMessageDispatcher);
        leasedScheduler.start();
        try {
            var messages = new CopyOnWriteArrayList<ControllerMessage>();
            sessionManager.register(new NodeSession(
                    "session-2",
                    "node-1",
                    new StreamObserver<>() {
                        @Override
                        public void onNext(ControllerMessage value) {
                            messages.add(value);
                        }

                        @Override
                        public void onError(Throwable t) {}

                        @Override
                        public void onCompleted() {}
                    },
                    Instant.now()));

            var group = stubGroup("lobby");
            when(groupManager.get("lobby")).thenReturn(Optional.of(group));
            when(groupManager.resolveGroup("lobby")).thenReturn(group);

            clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
            clusterState.addInstance(new InstanceInfo(
                    "lobby-lease", "lobby", "node-1", InstanceState.SCHEDULED, 25567, 0, 0, Instant.now()));
            when(stateStore.getInstanceCompositionPlan("lobby-lease"))
                    .thenReturn(Optional.of(compositionPlan("lobby-lease")));

            workflowStateStore.saveStartRetry(new StartRetryIntent(
                    "lobby-lease",
                    "lobby",
                    "node-1",
                    "RUNTIME_PROVISION_FAILED",
                    "plan-hash-123",
                    1,
                    Instant.now(),
                    Instant.now()));

            leasedScheduler.reconcilePersistedStartRetries();

            Thread.sleep(200);
            assertTrue(messages.isEmpty());
            assertTrue(workflowStateStore.getStartRetry("lobby-lease").isPresent());
        } finally {
            leasedScheduler.stop();
        }
    }

    @Test
    void reconcilePersistedStartRetriesSkipsDispatchWhenFenceTokenTurnsStale() throws Exception {
        InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
        var controllerALeaseManager = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
        var controllerBLeaseManager = new DistributedLeaseManager(redis.commands(), "controller-b", 60);

        var leasedScheduler = new Scheduler(
                groupManager,
                clusterState,
                scalingEvaluator,
                mock(CrashLoopDetector.class),
                stateStore,
                workflowStateStore,
                placementCoordinator,
                deploymentReconciler,
                30,
                () -> false,
                controllerALeaseManager,
                null,
                new NodeMessageDispatcher(sessionManager, null, redis.commands()));
        leasedScheduler.start();
        try {
            var messages = new CopyOnWriteArrayList<ControllerMessage>();
            sessionManager.register(new NodeSession(
                    "session-3",
                    "node-1",
                    new StreamObserver<>() {
                        @Override
                        public void onNext(ControllerMessage value) {
                            messages.add(value);
                        }

                        @Override
                        public void onError(Throwable t) {}

                        @Override
                        public void onCompleted() {}
                    },
                    Instant.now()));

            var group = stubGroup("lobby");
            when(groupManager.get("lobby")).thenReturn(Optional.of(group));
            when(groupManager.resolveGroup("lobby")).thenReturn(group);

            clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
            clusterState.addInstance(new InstanceInfo(
                    "lobby-fence", "lobby", "node-1", InstanceState.SCHEDULED, 25568, 0, 0, Instant.now()));
            var compositionPlan = compositionPlan("lobby-fence");
            when(stateStore.getInstanceCompositionPlan("lobby-fence")).thenAnswer(invocation -> {
                redis.delete(RedisKeys.lease("group:lobby"));
                assertTrue(
                        controllerBLeaseManager.tryAcquireLease("group:lobby").isPresent());
                return Optional.of(compositionPlan);
            });

            workflowStateStore.saveStartRetry(new StartRetryIntent(
                    "lobby-fence",
                    "lobby",
                    "node-1",
                    "RUNTIME_PROVISION_FAILED",
                    compositionPlan.planHash(),
                    1,
                    Instant.now(),
                    Instant.now()));

            leasedScheduler.reconcilePersistedStartRetries();

            Thread.sleep(200);
            assertTrue(messages.isEmpty());
            assertTrue(workflowStateStore.getStartRetry("lobby-fence").isPresent());
        } finally {
            leasedScheduler.stop();
        }
    }

    @Test
    void leaseAcquisitionTriggersStartRetryReconciliation() throws Exception {
        InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
        var leaseManager = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
        var leasedScheduler = new Scheduler(
                groupManager,
                clusterState,
                scalingEvaluator,
                mock(CrashLoopDetector.class),
                stateStore,
                workflowStateStore,
                placementCoordinator,
                deploymentReconciler,
                30,
                () -> false,
                leaseManager,
                null,
                nodeMessageDispatcher);
        try {
            var messages = new CopyOnWriteArrayList<ControllerMessage>();
            var sent = new CountDownLatch(1);
            sessionManager.register(new NodeSession(
                    "session-4",
                    "node-1",
                    new StreamObserver<>() {
                        @Override
                        public void onNext(ControllerMessage value) {
                            messages.add(value);
                            sent.countDown();
                        }

                        @Override
                        public void onError(Throwable t) {}

                        @Override
                        public void onCompleted() {}
                    },
                    Instant.now()));

            var group = stubGroup("lobby");
            when(groupManager.get("lobby")).thenReturn(Optional.of(group));
            when(groupManager.resolveGroup("lobby")).thenReturn(group);
            clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
            clusterState.addInstance(new InstanceInfo(
                    "lobby-handoff", "lobby", "node-1", InstanceState.SCHEDULED, 25569, 0, 0, Instant.now()));
            var compositionPlan = compositionPlan("lobby-handoff");
            when(stateStore.getInstanceCompositionPlan("lobby-handoff")).thenReturn(Optional.of(compositionPlan));
            workflowStateStore.saveStartRetry(new StartRetryIntent(
                    "lobby-handoff",
                    "lobby",
                    "node-1",
                    "RUNTIME_PROVISION_FAILED",
                    compositionPlan.planHash(),
                    1,
                    Instant.now(),
                    Instant.now()));

            leaseManager.tryAcquireLease("group:lobby").orElseThrow();

            assertTrue(sent.await(5, TimeUnit.SECONDS));
            assertEquals("lobby-handoff", messages.getFirst().getStartInstance().getInstanceId());
        } finally {
            leasedScheduler.stop();
        }
    }

    @Test
    void redisWakeupQueueResendsQueuedStart() throws Exception {
        var messages = new CopyOnWriteArrayList<ControllerMessage>();
        var sent = new CountDownLatch(1);
        sessionManager.register(new NodeSession(
                "session-queue",
                "node-1",
                new StreamObserver<>() {
                    @Override
                    public void onNext(ControllerMessage value) {
                        messages.add(value);
                        sent.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {}
                },
                Instant.now()));

        var group = stubGroup("lobby");
        when(groupManager.get("lobby")).thenReturn(Optional.of(group));
        when(groupManager.resolveGroup("lobby")).thenReturn(group);

        clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
        clusterState.addInstance(new InstanceInfo(
                "lobby-queue", "lobby", "node-1", InstanceState.SCHEDULED, 25570, 0, 0, Instant.now()));
        var compositionPlan = compositionPlan("lobby-queue");
        when(stateStore.getInstanceCompositionPlan("lobby-queue")).thenReturn(Optional.of(compositionPlan));

        var queueRedis = new InMemoryRetryWakeupRedis();
        var queuedScheduler = new Scheduler(
                groupManager,
                clusterState,
                scalingEvaluator,
                mock(CrashLoopDetector.class),
                stateStore,
                workflowStateStore,
                placementCoordinator,
                deploymentReconciler,
                30,
                () -> false,
                null,
                new RedisStartRetryWakeupQueue(queueRedis.commands(), "controller-a", 30),
                nodeMessageDispatcher);
        queuedScheduler.start();
        try {
            workflowStateStore.saveStartRetry(new StartRetryIntent(
                    "lobby-queue",
                    "lobby",
                    "node-1",
                    "RUNTIME_PROVISION_FAILED",
                    compositionPlan.planHash(),
                    1,
                    Instant.now(),
                    Instant.now()));

            queuedScheduler.reconcilePersistedStartRetries();

            assertTrue(sent.await(5, TimeUnit.SECONDS));
            assertEquals("lobby-queue", messages.getFirst().getStartInstance().getInstanceId());
        } finally {
            queuedScheduler.stop();
        }
    }

    private static InstanceCompositionPlan compositionPlan(String instanceId) {
        return new InstanceCompositionPlan(
                instanceId,
                "lobby",
                "node-1",
                25565,
                1024,
                new InstanceCompositionPlan.RuntimeIsolation(0.0, 0),
                List.of("-Xms512M"),
                Map.of("ENV", "value"),
                false,
                List.of(),
                List.of(new InstanceCompositionPlan.ResolvedTemplate("lobby", "hash-template", "group")),
                new InstanceCompositionPlan.ResolvedRuntime(
                        "server.jar",
                        "https://controller/runtime.jar",
                        "sha-runtime",
                        "PAPER",
                        "1.21.4",
                        "SERVER",
                        "paper",
                        "paper:1.21.4"),
                List.of(),
                List.of(),
                "plan-hash-123",
                Instant.now());
    }

    private static GroupConfig stubGroup(String name) {
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
                        case "scan" -> scan(args);
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

        private KeyScanCursor<String> scan(Object[] args) {
            KeyScanCursor<String> cursor = new KeyScanCursor<>();
            cursor.setCursor(ScanCursor.FINISHED.getCursor());
            cursor.setFinished(true);
            cursor.getKeys().addAll(values.keySet());
            return cursor;
        }

        private void delete(String key) {
            values.remove(key);
        }
    }

    private static final class InMemoryRetryWakeupRedis {

        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, java.util.NavigableMap<Double, java.util.Set<String>>> zsets =
                new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        private RedisCommands<String, String> commands() {
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                    RedisCommands.class.getClassLoader(),
                    new Class<?>[] {RedisCommands.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> set((String) args[0], (String) args[1], (SetArgs) args[2]);
                        case "get" -> values.get((String) args[0]);
                        case "del" -> deleteAll(args);
                        case "zadd" -> zadd((String) args[0], toDouble(args[1]), (String) args[2]);
                        case "zrem" -> zrem((String) args[0], args[1]);
                        case "zrangebyscore" -> zrangebyscore((String) args[0], toDouble(args[1]), toDouble(args[2]));
                        case "toString" -> "InMemoryRetryWakeupRedis";
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
                    deleted += zsets.remove(key) != null ? 1 : 0;
                } else if (rawKey instanceof String[] keys) {
                    for (String key : keys) {
                        deleted += values.remove(key) != null ? 1 : 0;
                        deleted += zsets.remove(key) != null ? 1 : 0;
                    }
                }
            }
            return deleted;
        }

        private long zadd(String key, double score, String member) {
            var sortedMembers = zsets.computeIfAbsent(key, ignored -> new java.util.TreeMap<>());
            sortedMembers.values().forEach(members -> members.remove(member));
            sortedMembers
                    .computeIfAbsent(score, ignored -> new java.util.LinkedHashSet<>())
                    .add(member);
            return 1L;
        }

        private long zrem(String key, Object rawMembers) {
            var sortedMembers = zsets.get(key);
            if (sortedMembers == null) {
                return 0L;
            }
            var members = new java.util.ArrayList<String>();
            if (rawMembers instanceof String member) {
                members.add(member);
            } else if (rawMembers instanceof String[] manyMembers) {
                members.addAll(List.of(manyMembers));
            }
            long removed = 0L;
            var iterator = sortedMembers.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                for (String member : members) {
                    if (entry.getValue().remove(member)) {
                        removed++;
                    }
                }
                if (entry.getValue().isEmpty()) {
                    iterator.remove();
                }
            }
            return removed;
        }

        private List<String> zrangebyscore(String key, double min, double max) {
            var sortedMembers = zsets.get(key);
            if (sortedMembers == null) {
                return List.of();
            }
            var result = new java.util.ArrayList<String>();
            for (var entry : sortedMembers.entrySet()) {
                if (entry.getKey() < min || entry.getKey() > max) {
                    continue;
                }
                result.addAll(entry.getValue());
            }
            return List.copyOf(result);
        }

        private double toDouble(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            return Double.parseDouble(String.valueOf(value));
        }
    }
}
