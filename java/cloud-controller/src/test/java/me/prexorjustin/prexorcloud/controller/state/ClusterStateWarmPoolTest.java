package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClusterState warm pool")
class ClusterStateWarmPoolTest {

    private ClusterState clusterState;

    @BeforeEach
    void setUp() {
        clusterState = new ClusterState(new EventBus());
    }

    private void add(String id, InstanceState state, boolean warm) {
        clusterState.addInstance(
                new InstanceInfo(id, "lobby", "node-1", state, 30000, 0, 1000, Instant.now()).withWarm(warm));
    }

    @Test
    @DisplayName("separates serving from warm and promotes a warm instance to serving")
    void promoteWarmToServing() {
        add("lobby-1", InstanceState.RUNNING, false); // serving
        add("lobby-2", InstanceState.RUNNING, true); // warm
        add("lobby-3", InstanceState.SCHEDULED, true); // warm, still coming up

        assertEquals(1, clusterState.servingInstances("lobby").size());
        assertEquals(2, clusterState.warmInstanceCount("lobby")); // includes the still-scheduled one

        var promoted = clusterState.promoteWarmInstance("lobby");
        assertTrue(promoted.isPresent());
        assertEquals("lobby-2", promoted.get().id(), "only a RUNNING warm instance is promotable");
        assertFalse(promoted.get().warm());

        assertEquals(2, clusterState.servingInstances("lobby").size());
        assertEquals(1, clusterState.warmInstanceCount("lobby"));
    }

    @Test
    @DisplayName("promote returns empty when no RUNNING warm instance exists")
    void promoteNoneAvailable() {
        add("lobby-1", InstanceState.RUNNING, false);
        add("lobby-2", InstanceState.SCHEDULED, true); // warm but not yet RUNNING

        assertTrue(clusterState.promoteWarmInstance("lobby").isEmpty());
    }

    @Test
    @DisplayName("markInstanceWarm holds a freshly placed instance back from serving")
    void markWarm() {
        add("lobby-1", InstanceState.RUNNING, false);
        clusterState.markInstanceWarm("lobby-1");

        assertEquals(0, clusterState.servingInstances("lobby").size());
        assertEquals(1, clusterState.warmInstanceCount("lobby"));
    }
}
