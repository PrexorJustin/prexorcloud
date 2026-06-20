package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClusterState — renewable readiness")
class ClusterStateReadinessTest {

    private EventBus eventBus;
    private ClusterState state;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        state = new ClusterState(eventBus);
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    @DisplayName("promotes a STARTING instance to RUNNING")
    void promotesStarting() {
        state.addInstance(instance("lobby-1", InstanceState.STARTING));
        state.renewInstanceReadiness("lobby-1");
        assertEquals(InstanceState.RUNNING, currentState("lobby-1"));
    }

    @Test
    @DisplayName("promotes PREPARING and SCHEDULED to RUNNING")
    void promotesPreReadyStates() {
        state.addInstance(instance("a", InstanceState.PREPARING));
        state.addInstance(instance("b", InstanceState.SCHEDULED));
        state.renewInstanceReadiness("a");
        state.renewInstanceReadiness("b");
        assertEquals(InstanceState.RUNNING, currentState("a"));
        assertEquals(InstanceState.RUNNING, currentState("b"));
    }

    @Test
    @DisplayName("is an idempotent no-op for an already-RUNNING instance")
    void idempotentForRunning() {
        state.addInstance(instance("lobby-1", InstanceState.RUNNING));
        state.renewInstanceReadiness("lobby-1");
        assertEquals(InstanceState.RUNNING, currentState("lobby-1"));
    }

    @Test
    @DisplayName("never un-drains a DRAINING instance")
    void respectsDraining() {
        state.addInstance(instance("lobby-1", InstanceState.DRAINING));
        state.renewInstanceReadiness("lobby-1");
        assertEquals(InstanceState.DRAINING, currentState("lobby-1"));
    }

    @Test
    @DisplayName("never reverses a deliberate STOPPING")
    void respectsStopping() {
        state.addInstance(instance("lobby-1", InstanceState.STOPPING));
        state.renewInstanceReadiness("lobby-1");
        assertEquals(InstanceState.STOPPING, currentState("lobby-1"));
    }

    @Test
    @DisplayName("does not resurrect a terminal CRASHED/STOPPED record")
    void respectsTerminal() {
        state.addInstance(instance("crashed", InstanceState.CRASHED));
        state.addInstance(instance("stopped", InstanceState.STOPPED));
        state.renewInstanceReadiness("crashed");
        state.renewInstanceReadiness("stopped");
        assertEquals(InstanceState.CRASHED, currentState("crashed"));
        assertEquals(InstanceState.STOPPED, currentState("stopped"));
    }

    @Test
    @DisplayName("is a silent no-op for an unknown instance")
    void noOpForUnknown() {
        assertDoesNotThrow(() -> state.renewInstanceReadiness("ghost"));
    }

    private InstanceState currentState(String id) {
        return state.instanceRegistry().get(id).orElseThrow().state();
    }

    private static InstanceInfo instance(String id, InstanceState initial) {
        return new InstanceInfo(id, "lobby", "node-1", initial, 25565, 0, 0, Instant.now());
    }
}
