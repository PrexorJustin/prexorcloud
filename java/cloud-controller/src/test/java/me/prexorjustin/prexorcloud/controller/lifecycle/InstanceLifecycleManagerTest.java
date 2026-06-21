package me.prexorjustin.prexorcloud.controller.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.HealingActionIntent;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

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
    private StateStore stateStore;
    private InstanceLifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        workflowStateStore = new WorkflowStateStore();
        groupManager = mock(GroupManager.class);
        scheduler = mock(Scheduler.class);
        consoleBuffer = new ConsoleBuffer();
        stateStore = mock(StateStore.class);
        lifecycleManager =
                new InstanceLifecycleManager(clusterState, eventBus, groupManager, consoleBuffer, stateStore);
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
    void nonLeaderIgnoresCrashEvents() throws InterruptedException {
        // A second (follower) manager shares the EventBus but is not the leader: it must not drive any
        // healing replacement. Ownership = leadership replaces the per-instance Redis healing lease.
        var otherScheduler = mock(Scheduler.class);
        var otherLifecycleManager =
                new InstanceLifecycleManager(clusterState, eventBus, groupManager, consoleBuffer, stateStore);
        otherLifecycleManager.setLeadership(notLeader());
        otherLifecycleManager.attachHealingWorkflow(workflowStateStore, otherScheduler);
        try {
            clusterState.updateInstanceState("lobby-1", InstanceState.CRASHED);
            Thread.sleep(200);

            // The follower ignored the crash; the leader (from setUp) reacted.
            verify(otherScheduler, never()).scheduleReplacement("lobby", "lobby-1");
            verify(scheduler).scheduleReplacement("lobby", "lobby-1");
        } finally {
            otherLifecycleManager.stop();
        }
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
}
