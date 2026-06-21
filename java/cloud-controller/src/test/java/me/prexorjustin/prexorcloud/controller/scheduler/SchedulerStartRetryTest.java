package me.prexorjustin.prexorcloud.controller.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentReconciler;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
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
    void reconcilePersistedStartRetriesClearsTerminalRetries() {
        clusterState.addInstance(
                new InstanceInfo("lobby-2", "lobby", "node-1", InstanceState.CRASHED, 25566, 0, 0, Instant.now()));
        workflowStateStore.saveStartRetry(new StartRetryIntent(
                "lobby-2", "lobby", "node-1", "RUNTIME_PROVISION_FAILED", "plan-2", 1, Instant.now(), Instant.now()));

        scheduler.reconcilePersistedStartRetries();

        assertFalse(workflowStateStore.getStartRetry("lobby-2").isPresent());
    }

    @Test
    void reconcilePersistedStartRetriesSkipsWhenNotLeader() throws Exception {
        // ownership = leadership: a follower must not resend a persisted start retry — the leader does.
        scheduler.setLeadership(notLeader());

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
                "lobby-follower", "lobby", "node-1", InstanceState.SCHEDULED, 25567, 0, 0, Instant.now()));
        when(stateStore.getInstanceCompositionPlan("lobby-follower"))
                .thenReturn(Optional.of(compositionPlan("lobby-follower")));

        workflowStateStore.saveStartRetry(new StartRetryIntent(
                "lobby-follower",
                "lobby",
                "node-1",
                "RUNTIME_PROVISION_FAILED",
                "plan-hash-123",
                1,
                Instant.now(),
                Instant.now()));

        scheduler.reconcilePersistedStartRetries();

        Thread.sleep(200);
        assertTrue(messages.isEmpty());
        assertTrue(workflowStateStore.getStartRetry("lobby-follower").isPresent());
    }

    @Test
    void reconcilePersistedStartRetriesSkipsDispatchWhenLeadershipLostMidWork() throws Exception {
        // The tick-level gate passes, but leadership is lost after the composition plan is fetched and
        // before dispatch. The fencing re-check (ensureLeaseCurrent) must abort the resend.
        var leader = new java.util.concurrent.atomic.AtomicBoolean(true);
        scheduler.setLeadership(new Leadership() {
            @Override
            public boolean isLeader() {
                return leader.get();
            }

            @Override
            public long currentEpoch() {
                return 1L;
            }
        });

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
            leader.set(false); // leadership lost after planning, before dispatch
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

        scheduler.reconcilePersistedStartRetries();

        Thread.sleep(200);
        assertTrue(messages.isEmpty());
        assertTrue(workflowStateStore.getStartRetry("lobby-fence").isPresent());
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

    private static Leadership notLeader() {
        return new Leadership() {
            @Override
            public boolean isLeader() {
                return false;
            }

            @Override
            public long currentEpoch() {
                return 0L;
            }
        };
    }
}
