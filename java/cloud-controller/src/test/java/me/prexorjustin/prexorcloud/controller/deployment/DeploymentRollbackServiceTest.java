package me.prexorjustin.prexorcloud.controller.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.group.GroupStore;
import me.prexorjustin.prexorcloud.controller.group.MongoGroupStore;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeploymentRollbackServiceTest {

    private static DeploymentRecord deployment(int id, int rev, String state, String groupSnapshot) {
        return new DeploymentRecord(
                id, "lobby", rev, "manual", "ROLLING", state, "{}", "{}", 1, 1,
                Instant.now().toString(), null, null, groupSnapshot);
    }

    @Test
    void restoresPriorCompletedConfigAndDispatchesRollbackDeployment() throws Exception {
        var stateStore = mock(StateStore.class);
        var groupManager = mock(GroupManager.class);
        var groupStore = mock(GroupStore.class);
        var eventBus = new EventBus();
        var clusterState = new ClusterState(eventBus);
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 30000, 0, 0, Instant.now()));

        String goodSnapshot = MongoGroupStore.toJson(minimal("lobby"));
        var failed = deployment(50, 5, "ROLLED_BACK", "{\"unparseable\":");
        var good = deployment(30, 3, "COMPLETED", goodSnapshot);
        // getDeployments returns most-recent-first; the rollback scans for the latest COMPLETED with a snapshot.
        when(stateStore.getDeployments("lobby", 50, 0)).thenReturn(List.of(failed, good));
        when(stateStore.getDeployments("lobby", 1, 0)).thenReturn(List.of(failed));
        when(stateStore.createDeployment(any())).thenAnswer(inv -> inv.getArgument(0));

        var redeployed = new CopyOnWriteArrayList<DeploymentRecord>();
        var service = new DeploymentRollbackService(
                stateStore, groupManager, groupStore, clusterState, eventBus, redeployed::add);

        assertTrue(service.rollback(failed));

        // The prior good config was restored (in-memory + persisted).
        var restored = ArgumentCaptor.forClass(GroupConfig.class);
        verify(groupManager).update(restored.capture());
        assertEquals("lobby", restored.getValue().name());
        verify(groupStore).save(any(GroupConfig.class));

        // A rollback deployment was created, linked to the failed revision, and re-dispatched.
        var created = ArgumentCaptor.forClass(DeploymentRecord.class);
        verify(stateStore).createDeployment(created.capture());
        assertEquals("rollback", created.getValue().trigger());
        assertEquals(Integer.valueOf(5), created.getValue().rollbackOf());
        assertEquals(6, created.getValue().revision());
        assertEquals("IN_PROGRESS", created.getValue().state());
        assertEquals(goodSnapshot, created.getValue().groupSnapshot());
        assertEquals(1, redeployed.size());
    }

    @Test
    void returnsFalseWhenNoPriorCompletedSnapshot() {
        var stateStore = mock(StateStore.class);
        var groupManager = mock(GroupManager.class);
        var groupStore = mock(GroupStore.class);
        var eventBus = new EventBus();
        var clusterState = new ClusterState(eventBus);

        var failed = deployment(50, 5, "ROLLED_BACK", "");
        // No prior COMPLETED deployment carrying a snapshot.
        when(stateStore.getDeployments("lobby", 50, 0)).thenReturn(List.of(failed));

        var redeployed = new CopyOnWriteArrayList<DeploymentRecord>();
        var service = new DeploymentRollbackService(
                stateStore, groupManager, groupStore, clusterState, eventBus, redeployed::add);

        assertFalse(service.rollback(failed));
        verify(groupManager, never()).update(any());
        verify(stateStore, never()).createDeployment(any());
        assertTrue(redeployed.isEmpty());
    }

    private static GroupConfig minimal(String name) {
        return new GroupConfig(
                name,
                null,
                "PAPER",
                "1.21",
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                10,
                100,
                0.8,
                300,
                30,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
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
