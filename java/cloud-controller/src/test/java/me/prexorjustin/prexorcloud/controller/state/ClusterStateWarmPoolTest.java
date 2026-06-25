package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.event.events.InstanceWarmChangedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClusterState warm pool")
class ClusterStateWarmPoolTest {

    private ClusterState clusterState;
    private final CopyOnWriteArrayList<InstanceWarmChangedEvent> warmEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        EventBus eventBus = new EventBus();
        eventBus.subscribe(InstanceWarmChangedEvent.class, warmEvents::add);
        clusterState = new ClusterState(eventBus);
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

    @Test
    @DisplayName("broadcasts a warm-flag change on promote and mark so proxies update routing")
    void broadcastsWarmFlagChanges() throws InterruptedException {
        add("lobby-1", InstanceState.RUNNING, false);
        warmEvents.clear(); // addInstance does not touch warm

        clusterState.markInstanceWarm("lobby-1");
        clusterState.markInstanceWarm("lobby-1"); // already warm — must not re-broadcast
        clusterState.promoteWarmInstance("lobby");

        waitForCount(2); // one mark + one promote (async EventBus)
        Thread.sleep(100); // settle so a spurious 3rd event from the redundant mark would surface

        assertEquals(2, warmEvents.size(), "redundant mark must not re-broadcast");
        assertTrue(warmEvents.get(0).warm(), "mark broadcasts warm=true");
        assertEquals("lobby-1", warmEvents.get(0).instanceId());
        assertFalse(warmEvents.get(1).warm(), "promote broadcasts warm=false");
    }

    private void waitForCount(int min) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (warmEvents.size() < min && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(warmEvents.size() >= min, "expected at least " + min + " warm events, got " + warmEvents.size());
    }
}
