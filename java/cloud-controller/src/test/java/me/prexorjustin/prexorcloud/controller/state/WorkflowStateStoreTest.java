package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WorkflowStateStoreTest {

    @Test
    void queuedTransferCanBeAcknowledged() {
        var store = new WorkflowStateStore();
        var playerId = UUID.randomUUID();

        store.queueTransfer(playerId, "target-1");

        assertEquals("target-1", store.pendingTransfers().get(playerId));

        store.ackTransfer(playerId);

        assertFalse(store.pendingTransfers().containsKey(playerId));
    }

    @Test
    void persistsAndHydratesWorkflowIntent() {
        var playerId = UUID.randomUUID();
        var stateStore = mock(StateStore.class);
        when(stateStore.getTransferIntents())
                .thenReturn(List.of(new TransferIntent(playerId, "target-1", Instant.parse("2026-04-14T00:00:00Z"))));
        when(stateStore.getNodeDrainIntents())
                .thenReturn(List.of(new NodeDrainIntent(
                        "node-1",
                        true,
                        "Maintenance",
                        Instant.parse("2026-04-14T00:00:00Z"),
                        Instant.parse("2026-04-14T00:05:00Z"),
                        Set.of("instance-1"))));
        when(stateStore.getHealingActionIntents()).thenReturn(List.of());
        when(stateStore.getStartRetryIntents())
                .thenReturn(List.of(new StartRetryIntent(
                        "lobby-1",
                        "lobby",
                        "node-1",
                        "RUNTIME_PROVISION_FAILED",
                        "plan-123",
                        2,
                        Instant.parse("2026-04-14T00:06:00Z"),
                        Instant.parse("2026-04-14T00:01:00Z"))));

        var store = new WorkflowStateStore(stateStore);

        assertEquals("target-1", store.pendingTransfers().get(playerId));
        assertTrue(store.getNodeDrain("node-1").isPresent());
        assertTrue(store.getStartRetry("lobby-1").isPresent());

        store.queueTransfer(playerId, "target-2");
        verify(stateStore).saveTransferIntent(any(TransferIntent.class));

        var intent = store.getNodeDrain("node-1").orElseThrow();
        store.saveNodeDrain(intent);
        verify(stateStore).saveNodeDrainIntent(intent);

        store.ackTransfer(playerId);
        verify(stateStore).deleteTransferIntent(playerId);

        store.deleteNodeDrain("node-1");
        verify(stateStore).deleteNodeDrainIntent("node-1");

        var retryIntent = new StartRetryIntent(
                "lobby-1",
                "lobby",
                "node-1",
                "RUNTIME_PROVISION_FAILED",
                "plan-123",
                3,
                Instant.parse("2026-04-14T00:07:00Z"),
                Instant.parse("2026-04-14T00:01:00Z"));
        store.saveStartRetry(retryIntent);
        verify(stateStore).saveStartRetryIntent(retryIntent);

        store.deleteStartRetry("lobby-1");
        verify(stateStore).deleteStartRetryIntent("lobby-1");
    }
}
