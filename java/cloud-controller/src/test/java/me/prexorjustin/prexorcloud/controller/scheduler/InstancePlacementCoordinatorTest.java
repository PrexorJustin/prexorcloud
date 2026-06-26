package me.prexorjustin.prexorcloud.controller.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef;
import me.prexorjustin.prexorcloud.controller.group.spec.secret.EnvSecretBackend;
import me.prexorjustin.prexorcloud.controller.group.spec.secret.SecretResolver;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.InstanceCompositionPlan;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.InstanceCompositionPlanner;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InstancePlacementCoordinatorTest {

    private EventBus eventBus;
    private ClusterState clusterState;
    private NodeSessionManager sessionManager;
    private StateStore stateStore;
    private NodeSelector nodeSelector;
    private ScalingEvaluator scalingEvaluator;
    private InstanceCompositionPlanner compositionPlanner;
    private InstancePlacementCoordinator coordinator;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        sessionManager = new NodeSessionManager();
        stateStore = mock(StateStore.class);
        nodeSelector = mock(NodeSelector.class);
        scalingEvaluator = mock(ScalingEvaluator.class);
        compositionPlanner = mock(InstanceCompositionPlanner.class);
        coordinator = new InstancePlacementCoordinator(
                clusterState,
                nodeSelector,
                scalingEvaluator,
                stateStore,
                compositionPlanner,
                new NodeMessageDispatcher(sessionManager),
                "http://localhost:8080");
        clusterState.addNode("node-1", "10.0.0.1", 4096, Map.of(), Instant.now(), null);
        when(nodeSelector.select(any(), any()))
                .thenAnswer(
                        invocation -> Optional.of(clusterState.getNode("node-1").orElseThrow()));
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    void placeResolvedInstancePersistsPlanAndDispatchesStart() {
        var messages = new CopyOnWriteArrayList<ControllerMessage>();
        sessionManager.register(new NodeSession(
                "session-1",
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
        var compositionPlan = compositionPlan("lobby-1");
        when(compositionPlanner.plan(
                        eq(group),
                        eq("lobby-1"),
                        eq("node-1"),
                        eq(30000),
                        eq("http://localhost:8080"),
                        eq(java.util.Map.of())))
                .thenReturn(compositionPlan);
        var clearedRetries = new CopyOnWriteArrayList<String>();

        boolean placed =
                coordinator.placeResolvedInstance(group, "lobby-1", (groupName, action) -> true, clearedRetries::add);

        assertTrue(placed);
        assertEquals(List.of("lobby-1"), clearedRetries);
        assertTrue(clusterState.getInstance("lobby-1").isPresent());
        assertFalse(messages.isEmpty());
        assertEquals("lobby-1", messages.getFirst().getStartInstance().getInstanceId());
        assertEquals(
                compositionPlan.planHash(),
                messages.getFirst().getStartInstance().getCompositionPlan().getPlanHash());
        assertEquals(
                compositionPlan.isolation().cpuReservation(),
                messages.getFirst().getStartInstance().getIsolation().getCpuReservation());
        assertEquals(
                compositionPlan.isolation().diskReservationMb(),
                messages.getFirst().getStartInstance().getIsolation().getDiskReservationMb());
        assertEquals(1024L, clusterState.getNode("node-1").orElseThrow().usedMemoryMb());
        assertTrue(clusterState.getNode("node-1").orElseThrow().usedPorts().contains(30000));
        verify(stateStore).saveInstanceCompositionPlan(compositionPlan);
        verify(scalingEvaluator).recordScaleAction("lobby");
    }

    @Test
    void defersWithoutDispatchingWhenNotLeader() {
        // ownership = leadership: a non-leader must persist the SCHEDULED placement but NOT issue a
        // token or dispatch — even though it holds the daemon session. The leader picks it up.
        coordinator.setLeadership(new Leadership() {
            @Override
            public boolean isLeader() {
                return false;
            }

            @Override
            public long currentEpoch() {
                return 0L;
            }
        });
        var messages = new CopyOnWriteArrayList<ControllerMessage>();
        sessionManager.register(new NodeSession(
                "session-1",
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
        var compositionPlan = compositionPlan("lobby-1");
        when(compositionPlanner.plan(
                        eq(group),
                        eq("lobby-1"),
                        eq("node-1"),
                        eq(30000),
                        eq("http://localhost:8080"),
                        eq(java.util.Map.of())))
                .thenReturn(compositionPlan);

        boolean placed = coordinator.placeResolvedInstance(group, "lobby-1", (groupName, action) -> true, retry -> {});

        assertTrue(placed, "placement returns true — the record is durably persisted for the leader");
        assertTrue(clusterState.getInstance("lobby-1").isPresent());
        verify(stateStore).saveInstanceCompositionPlan(compositionPlan);
        assertTrue(messages.isEmpty(), "a non-leader must not dispatch the start command");
        verify(scalingEvaluator, never()).recordScaleAction(any());
    }

    @Test
    void placeResolvedInstanceRollsBackWhenPlanningFails() {
        var group = stubGroup("lobby");
        when(compositionPlanner.plan(
                        eq(group),
                        eq("lobby-2"),
                        eq("node-1"),
                        eq(30000),
                        eq("http://localhost:8080"),
                        eq(java.util.Map.of())))
                .thenThrow(new IllegalStateException("boom"));
        var clearedRetries = new CopyOnWriteArrayList<String>();

        boolean placed =
                coordinator.placeResolvedInstance(group, "lobby-2", (groupName, action) -> true, clearedRetries::add);

        assertFalse(placed);
        assertFalse(clusterState.getInstance("lobby-2").isPresent());
        assertEquals(List.of("lobby-2", "lobby-2"), clearedRetries);
        assertEquals(0L, clusterState.getNode("node-1").orElseThrow().usedMemoryMb());
        assertFalse(clusterState.getNode("node-1").orElseThrow().usedPorts().contains(30000));
        verify(stateStore, never()).saveInstanceCompositionPlan(any());
        verify(stateStore).deleteInstanceCompositionPlan("lobby-2");
        verify(scalingEvaluator, never()).recordScaleAction(any());
    }

    @Test
    void placeResolvedInstancePreservesRecoverablePlacementWhenStartDispatchFails() {
        var group = stubGroup("lobby");
        var compositionPlan = compositionPlan("lobby-3");
        when(compositionPlanner.plan(
                        eq(group),
                        eq("lobby-3"),
                        eq("node-1"),
                        eq(30000),
                        eq("http://localhost:8080"),
                        eq(java.util.Map.of())))
                .thenReturn(compositionPlan);
        var clearedRetries = new CopyOnWriteArrayList<String>();

        boolean placed =
                coordinator.placeResolvedInstance(group, "lobby-3", (groupName, action) -> true, clearedRetries::add);

        assertTrue(placed);
        assertTrue(clusterState.getInstance("lobby-3").isPresent());
        assertEquals(List.of("lobby-3"), clearedRetries);
        assertEquals(1024L, clusterState.getNode("node-1").orElseThrow().usedMemoryMb());
        assertTrue(clusterState.getNode("node-1").orElseThrow().usedPorts().contains(30000));
        verify(stateStore).saveInstanceCompositionPlan(compositionPlan);
        verify(stateStore, never()).deleteInstanceCompositionPlan("lobby-3");
        verify(scalingEvaluator, never()).recordScaleAction(any());
    }

    @Test
    void placeResolvedInstancePreservesRecoverablePlacementWhenLeadershipLostAfterPlanning() {
        var group = stubGroup("lobby");
        var compositionPlan = compositionPlan("lobby-4");
        when(compositionPlanner.plan(
                        eq(group),
                        eq("lobby-4"),
                        eq("node-1"),
                        eq(30000),
                        eq("http://localhost:8080"),
                        eq(java.util.Map.of())))
                .thenReturn(compositionPlan);
        var clearedRetries = new CopyOnWriteArrayList<String>();
        var fenceChecks = new java.util.concurrent.atomic.AtomicInteger();

        boolean placed = coordinator.placeResolvedInstance(
                group,
                "lobby-4",
                (groupName, action) -> {
                    int attempt = fenceChecks.incrementAndGet();
                    return attempt < 3;
                },
                clearedRetries::add);

        assertTrue(placed);
        assertTrue(clusterState.getInstance("lobby-4").isPresent());
        assertEquals(List.of("lobby-4"), clearedRetries);
        assertEquals(1024L, clusterState.getNode("node-1").orElseThrow().usedMemoryMb());
        assertTrue(clusterState.getNode("node-1").orElseThrow().usedPorts().contains(30000));
        verify(stateStore).saveInstanceCompositionPlan(compositionPlan);
        verify(stateStore, never()).deleteInstanceCompositionPlan("lobby-4");
        verify(scalingEvaluator, never()).recordScaleAction(any());
    }

    @Test
    void dispatchResolvesSecretVariableReferencesToPlaintextInStartMessage() {
        var messages = captureDispatchedMessages();

        var group = stubGroup("lobby");
        var plan = compositionPlan("lobby-1");
        when(compositionPlanner.plan(
                        eq(group), eq("lobby-1"), eq("node-1"), eq(30000), eq("http://localhost:8080"), eq(Map.of())))
                .thenReturn(plan);
        // The "lobby" template declares a SECRET whose value is an env:// reference, not the secret.
        when(stateStore.getTemplateVariableDefs("lobby"))
                .thenReturn(List.of(new VariableDef(
                        "RCON_PASSWORD",
                        VariableDef.VarType.SECRET,
                        "env://RCON_SECRET",
                        false,
                        null,
                        VariableDef.Scope.INSTANCE,
                        VariableDef.Visibility.ADMIN,
                        "")));
        coordinator.setSecretResolver(new SecretResolver(
                List.of(new EnvSecretBackend(name -> "RCON_SECRET".equals(name) ? "s3cr3t!" : null))));

        assertTrue(coordinator.placeResolvedInstance(group, "lobby-1", (g, action) -> true, x -> {}));

        // The daemon receives the fetched plaintext, never the env:// reference.
        var resolved = messages.getFirst().getStartInstance().getResolvedVariablesMap();
        assertEquals("s3cr3t!", resolved.get("RCON_PASSWORD"));
    }

    @Test
    void dispatchDropsAnUnresolvableSecretButStillStartsTheInstance() {
        var messages = captureDispatchedMessages();

        var group = stubGroup("lobby");
        var plan = compositionPlan("lobby-1");
        when(compositionPlanner.plan(
                        eq(group), eq("lobby-1"), eq("node-1"), eq(30000), eq("http://localhost:8080"), eq(Map.of())))
                .thenReturn(plan);
        when(stateStore.getTemplateVariableDefs("lobby"))
                .thenReturn(List.of(new VariableDef(
                        "RCON_PASSWORD",
                        VariableDef.VarType.SECRET,
                        "env://MISSING_SECRET",
                        false,
                        null,
                        VariableDef.Scope.INSTANCE,
                        VariableDef.Visibility.ADMIN,
                        "")));
        coordinator.setSecretResolver(new SecretResolver(List.of(new EnvSecretBackend(name -> null))));

        // A misconfigured secret must never wedge a start: the key is dropped, the instance still goes.
        assertTrue(coordinator.placeResolvedInstance(group, "lobby-1", (g, action) -> true, x -> {}));

        var resolved = messages.getFirst().getStartInstance().getResolvedVariablesMap();
        assertFalse(resolved.containsKey("RCON_PASSWORD"));
    }

    private CopyOnWriteArrayList<ControllerMessage> captureDispatchedMessages() {
        var messages = new CopyOnWriteArrayList<ControllerMessage>();
        sessionManager.register(new NodeSession(
                "session-1",
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
        return messages;
    }

    private static InstanceCompositionPlan compositionPlan(String instanceId) {
        return new InstanceCompositionPlan(
                instanceId,
                "lobby",
                "node-1",
                30000,
                1024,
                new InstanceCompositionPlan.RuntimeIsolation(0.25, 4096),
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
                Map.of(),
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
}
