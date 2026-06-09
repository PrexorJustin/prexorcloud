package me.prexorjustin.prexorcloud.controller.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import me.prexorjustin.prexorcloud.api.event.events.InstanceStateChangedEvent;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.crash.CrashStore;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.session.HeartbeatTracker;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.template.TemplateManager;
import me.prexorjustin.prexorcloud.controller.template.TemplateMerger;
import me.prexorjustin.prexorcloud.protocol.CrashReport;
import me.prexorjustin.prexorcloud.protocol.DaemonMessage;
import me.prexorjustin.prexorcloud.protocol.Handshake;
import me.prexorjustin.prexorcloud.protocol.InstanceState;
import me.prexorjustin.prexorcloud.protocol.InstanceStatusUpdate;
import me.prexorjustin.prexorcloud.protocol.Pong;
import me.prexorjustin.prexorcloud.protocol.StartFailureDisposition;
import me.prexorjustin.prexorcloud.protocol.StartInstanceAck;
import me.prexorjustin.prexorcloud.protocol.StartPreparationStage;

import io.grpc.stub.StreamObserver;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DaemonServiceImplTest {

    private static PendingRequestRegistry newPendingRequestRegistry() {
        return new PendingRequestRegistry(java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-pending-request");
            t.setDaemon(true);
            return t;
        }));
    }

    private EventBus eventBus;
    private ClusterState clusterState;
    private CrashStore crashStore;
    private NodeSessionManager sessionManager;
    private DaemonServiceImpl service;
    private RecordingObserver responseObserver;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        crashStore = new CrashStore(16);

        sessionManager = new NodeSessionManager();
        var heartbeatTracker = new HeartbeatTracker(sessionManager, clusterState, eventBus, 3);
        var crashLoopDetector = new CrashLoopDetector(3, 60, eventBus);
        var stateStore = mock(StateStore.class);

        service = new DaemonServiceImpl(
                new DaemonServiceImpl.Deps(
                        sessionManager,
                        clusterState,
                        heartbeatTracker,
                        eventBus,
                        crashStore,
                        crashLoopDetector,
                        mock(TemplateManager.class),
                        mock(TemplateMerger.class),
                        stateStore,
                        new ConsoleBuffer(),
                        mock(GroupManager.class),
                        mock(CatalogStore.class),
                        null,
                        null,
                        newPendingRequestRegistry(),
                        null),
                30_000,
                3,
                8080);
        responseObserver = new RecordingObserver();
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    void ignoresInstanceStatusBeforeHandshake() {
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.STARTING, 25565, 0, 0, Instant.now()));

        var requestObserver = service.connect(responseObserver);
        requestObserver.onNext(DaemonMessage.newBuilder()
                .setInstanceStatus(InstanceStatusUpdate.newBuilder()
                        .setInstanceId("lobby-1")
                        .setState(InstanceState.RUNNING)
                        .build())
                .build());

        assertEquals(
                InstanceState.STARTING,
                clusterState.getInstance("lobby-1").orElseThrow().state());
    }

    @Test
    void rejectsSpoofedCrashReportsFromOtherNode() {
        var requestObserver = connectNode("node-2");
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));

        requestObserver.onNext(DaemonMessage.newBuilder()
                .setCrashReport(CrashReport.newBuilder()
                        .setInstanceId("lobby-1")
                        .setGroup("lobby")
                        .setExitCode(1)
                        .setUptimeMs(1_000)
                        .addLogTail("boom")
                        .build())
                .build());

        assertEquals(
                InstanceState.RUNNING,
                clusterState.getInstance("lobby-1").orElseThrow().state());
        assertEquals(0, crashStore.size());
    }

    @Test
    void rejectsStaleStatusAfterTerminalTransition() {
        var requestObserver = connectNode("node-1");
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.STARTING, 25565, 0, 0, Instant.now()));

        requestObserver.onNext(status("lobby-1", InstanceState.STOPPED));
        requestObserver.onNext(status("lobby-1", InstanceState.RUNNING));

        assertEquals(
                InstanceState.STOPPED,
                clusterState.getInstance("lobby-1").orElseThrow().state());
    }

    @Test
    void duplicateStatusDoesNotRepublishTransition() throws InterruptedException {
        var requestObserver = connectNode("node-1");
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.STARTING, 25565, 0, 0, Instant.now()));
        List<InstanceStateChangedEvent> transitions = new ArrayList<>();
        eventBus.subscribe(InstanceStateChangedEvent.class, transitions::add);

        requestObserver.onNext(status("lobby-1", InstanceState.RUNNING));
        requestObserver.onNext(status("lobby-1", InstanceState.RUNNING));
        Thread.sleep(200);

        assertEquals(1, transitions.size());
        assertEquals(
                InstanceState.RUNNING,
                clusterState.getInstance("lobby-1").orElseThrow().state());
    }

    @Test
    void transientStartAckRequeuesRetryAndReturnsInstanceToScheduled() {
        var scheduler = mock(Scheduler.class);
        service.attachScheduler(scheduler);
        when(scheduler.retryStart("lobby-1", 7, "RUNTIME_PROVISION_FAILED")).thenReturn(true);

        var requestObserver = connectNode("node-1");
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.PREPARING, 25565, 0, 0, Instant.now()));

        requestObserver.onNext(DaemonMessage.newBuilder()
                .setStartInstanceAck(StartInstanceAck.newBuilder()
                        .setInstanceId("lobby-1")
                        .setAccepted(false)
                        .setStage(StartPreparationStage.RUNTIME_PROVISION)
                        .setErrorCode("RUNTIME_PROVISION_FAILED")
                        .setErrorMessage("temporary download failure")
                        .setFailureDisposition(StartFailureDisposition.TRANSIENT)
                        .setRetryAfterSeconds(7))
                .build());

        assertEquals(
                InstanceState.SCHEDULED,
                clusterState.getInstance("lobby-1").orElseThrow().state());
        verify(scheduler).retryStart("lobby-1", 7, "RUNTIME_PROVISION_FAILED");
        verify(scheduler, never()).clearStartRetryBudget("lobby-1");
    }

    @Test
    void permanentStartAckMarksInstanceCrashed() {
        var scheduler = mock(Scheduler.class);
        service.attachScheduler(scheduler);

        var requestObserver = connectNode("node-1");
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.SCHEDULED, 25565, 0, 0, Instant.now()));

        requestObserver.onNext(DaemonMessage.newBuilder()
                .setStartInstanceAck(StartInstanceAck.newBuilder()
                        .setInstanceId("lobby-1")
                        .setAccepted(false)
                        .setStage(StartPreparationStage.VALIDATION)
                        .setErrorCode("INVALID_GROUP")
                        .setErrorMessage("group invalid")
                        .setFailureDisposition(StartFailureDisposition.PERMANENT))
                .build());

        assertEquals(
                InstanceState.CRASHED,
                clusterState.getInstance("lobby-1").orElseThrow().state());
        verify(scheduler).clearStartRetryBudget("lobby-1");
        verify(scheduler, never())
                .retryStart(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void duplicateStartAckDoesNotMarkInstanceCrashed() {
        var scheduler = mock(Scheduler.class);
        service.attachScheduler(scheduler);

        var requestObserver = connectNode("node-1");
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.SCHEDULED, 25565, 0, 0, Instant.now()));

        requestObserver.onNext(DaemonMessage.newBuilder()
                .setStartInstanceAck(StartInstanceAck.newBuilder()
                        .setInstanceId("lobby-1")
                        .setAccepted(false)
                        .setStage(StartPreparationStage.VALIDATION)
                        .setErrorCode("INSTANCE_ALREADY_STARTING")
                        .setErrorMessage("instance is already starting")
                        .setFailureDisposition(StartFailureDisposition.PERMANENT))
                .build());

        assertEquals(
                InstanceState.SCHEDULED,
                clusterState.getInstance("lobby-1").orElseThrow().state());
        verify(scheduler).clearStartRetryBudget("lobby-1");
        verify(scheduler, never())
                .retryStart(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void handshakeTriggersRecoverableStartReconciliationForNode() {
        var scheduler = mock(Scheduler.class);
        service.attachScheduler(scheduler);

        var requestObserver = service.connect(responseObserver);
        requestObserver.onNext(DaemonMessage.newBuilder()
                .setHandshake(Handshake.newBuilder()
                        .setNodeId("node-1")
                        .setVersion("1.0.0")
                        .setProtocolVersion(1)
                        .build())
                .build());

        verify(scheduler).reconcileRecoverableStartsForNode("node-1");
    }

    @Test
    void reconnectingNodeReplacesOldSessionWithoutDisconnectingCurrentNode() {
        var firstObserver = new RecordingObserver();
        var firstStream = service.connect(firstObserver);
        firstStream.onNext(DaemonMessage.newBuilder()
                .setHandshake(Handshake.newBuilder()
                        .setNodeId("node-1")
                        .setVersion("1.0.0")
                        .setProtocolVersion(1)
                        .build())
                .build());

        var secondObserver = new RecordingObserver();
        var secondStream = service.connect(secondObserver);
        secondStream.onNext(DaemonMessage.newBuilder()
                .setHandshake(Handshake.newBuilder()
                        .setNodeId("node-1")
                        .setVersion("1.0.0")
                        .setProtocolVersion(1)
                        .build())
                .build());

        assertEquals(1, sessionManager.sessionCount());
        assertTrue(sessionManager.getByNodeId("node-1").isPresent());

        firstStream.onCompleted();

        assertTrue(clusterState.getNode("node-1").isPresent());
        assertEquals(1, sessionManager.sessionCount());
        assertTrue(sessionManager.getByNodeId("node-1").isPresent());
    }

    @Test
    void handshakePersistsNodeOwnershipWithTtlAndPongRefreshesIt() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        var sessionManager = new NodeSessionManager();
        var serviceWithRedis = new DaemonServiceImpl(
                new DaemonServiceImpl.Deps(
                        sessionManager,
                        clusterState,
                        new HeartbeatTracker(sessionManager, clusterState, eventBus, 3),
                        eventBus,
                        crashStore,
                        new CrashLoopDetector(3, 60, eventBus),
                        mock(TemplateManager.class),
                        mock(TemplateMerger.class),
                        mock(StateStore.class),
                        new ConsoleBuffer(),
                        mock(GroupManager.class),
                        mock(CatalogStore.class),
                        redisCommands,
                        "controller-a",
                        newPendingRequestRegistry(),
                        null),
                30_000,
                3,
                8080);

        var requestObserver = serviceWithRedis.connect(responseObserver);
        requestObserver.onNext(DaemonMessage.newBuilder()
                .setHandshake(Handshake.newBuilder()
                        .setNodeId("node-ttl")
                        .setVersion("1.0.0")
                        .setProtocolVersion(1)
                        .build())
                .build());

        long expectedTtlSeconds = RedisKeys.nodeOwnerTtl(30_000, 3).getSeconds();
        verify(redisCommands).setex(RedisKeys.nodeOwner("node-ttl"), expectedTtlSeconds, "controller-a");

        requestObserver.onNext(DaemonMessage.newBuilder()
                .setPong(Pong.newBuilder().setSequence(1).build())
                .build());

        verify(redisCommands).expire(RedisKeys.nodeOwner("node-ttl"), expectedTtlSeconds);
    }

    @Test
    void cleanupRemovesNodeOwnershipHintFromRedis() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        var sessionManager = new NodeSessionManager();
        var serviceWithRedis = new DaemonServiceImpl(
                new DaemonServiceImpl.Deps(
                        sessionManager,
                        clusterState,
                        new HeartbeatTracker(sessionManager, clusterState, eventBus, 3),
                        eventBus,
                        crashStore,
                        new CrashLoopDetector(3, 60, eventBus),
                        mock(TemplateManager.class),
                        mock(TemplateMerger.class),
                        mock(StateStore.class),
                        new ConsoleBuffer(),
                        mock(GroupManager.class),
                        mock(CatalogStore.class),
                        redisCommands,
                        "controller-a",
                        newPendingRequestRegistry(),
                        null),
                30_000,
                3,
                8080);

        var requestObserver = serviceWithRedis.connect(responseObserver);
        requestObserver.onNext(DaemonMessage.newBuilder()
                .setHandshake(Handshake.newBuilder()
                        .setNodeId("node-cleanup")
                        .setVersion("1.0.0")
                        .setProtocolVersion(1)
                        .build())
                .build());
        requestObserver.onCompleted();

        verify(redisCommands).del(RedisKeys.nodeOwner("node-cleanup"));
    }

    private StreamObserver<DaemonMessage> connectNode(String nodeId) {
        var requestObserver = service.connect(responseObserver);
        requestObserver.onNext(DaemonMessage.newBuilder()
                .setHandshake(Handshake.newBuilder()
                        .setNodeId(nodeId)
                        .setVersion("1.0.0")
                        .setProtocolVersion(1)
                        .build())
                .build());
        return requestObserver;
    }

    private static DaemonMessage status(String instanceId, InstanceState state) {
        return DaemonMessage.newBuilder()
                .setInstanceStatus(InstanceStatusUpdate.newBuilder()
                        .setInstanceId(instanceId)
                        .setState(state)
                        .build())
                .build();
    }

    private static final class RecordingObserver
            implements StreamObserver<me.prexorjustin.prexorcloud.protocol.ControllerMessage> {

        private Throwable error;

        @Override
        public void onNext(me.prexorjustin.prexorcloud.protocol.ControllerMessage value) {}

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {}

        Throwable error() {
            return error;
        }
    }
}
