package me.prexorjustin.prexorcloud.controller.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.HealingActionIntent;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealingReconcilerTest {

    private ClusterState clusterState;
    private WorkflowStateStore workflowStateStore;
    private HealingReconciler healingReconciler;
    private HealingReconciler.ReplacementAction replacementAction;

    @BeforeEach
    void setUp() {
        clusterState = new ClusterState(new EventBus());
        workflowStateStore = new WorkflowStateStore();
        healingReconciler = new HealingReconciler(clusterState);
        replacementAction = mock(HealingReconciler.ReplacementAction.class);
        healingReconciler.attachWorkflow(workflowStateStore, replacementAction);
    }

    @Test
    void queueHealingPersistsIntentAndSchedulesReplacement() {
        healingReconciler.queueHealing("lobby-1", "lobby", "INSTANCE_CRASHED");

        assertTrue(workflowStateStore.getHealingAction("lobby-1").isPresent());
        verify(replacementAction).scheduleReplacement("lobby", "lobby-1");
    }

    @Test
    void reconcileClearsIntentWhenInstanceRecovered() {
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-1", "lobby", "INSTANCE_CRASHED", Instant.now()));
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));

        healingReconciler.reconcilePersistedHealingActions();

        assertFalse(workflowStateStore.getHealingAction("lobby-1").isPresent());
        verify(replacementAction, never()).scheduleReplacement("lobby", "lobby-1");
    }

    @Test
    void reconcileSchedulesReplacementForTerminalOrMissingInstance() {
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-1", "lobby", "INSTANCE_CRASHED", Instant.now()));
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-2", "lobby", "INSTANCE_CRASHED", Instant.now()));
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.CRASHED, 25565, 0, 0, Instant.now()));

        healingReconciler.reconcilePersistedHealingActions();

        verify(replacementAction).scheduleReplacement("lobby", "lobby-1");
        verify(replacementAction).scheduleReplacement("lobby", "lobby-2");
    }
}
