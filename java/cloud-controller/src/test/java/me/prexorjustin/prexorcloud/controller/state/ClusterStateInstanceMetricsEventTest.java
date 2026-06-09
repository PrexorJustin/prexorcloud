package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.event.events.InstanceMetricsUpdatedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClusterState — InstanceMetricsUpdatedEvent payload")
class ClusterStateInstanceMetricsEventTest {

    private EventBus eventBus;
    private ClusterState state;
    private CopyOnWriteArrayList<InstanceMetricsUpdatedEvent> received;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        state = new ClusterState(eventBus);
        received = new CopyOnWriteArrayList<>();
        eventBus.subscribe(InstanceMetricsUpdatedEvent.class, received::add);
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    @DisplayName("emits full metrics envelope including worlds, GC and server version")
    void fullEnvelope() throws Exception {
        state.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));

        var worlds = List.of(
                new InstanceMetrics.WorldSnapshot("world", "NORMAL", 120, 30, 2),
                new InstanceMetrics.WorldSnapshot("world_nether", "NETHER", 5, 4, 0));
        state.updateInstanceMetrics(new InstanceMetrics(
                "lobby-1",
                19.8,
                19.6,
                19.5,
                52.4,
                256L,
                1024L,
                384L,
                17L,
                230L,
                42,
                21,
                3,
                100,
                100,
                125L,
                34L,
                worlds,
                "Paper 1.21.4",
                7,
                90_000L,
                Instant.now()));

        var ev = waitForLast();
        assertEquals("lobby-1", ev.instanceId());
        assertEquals("lobby", ev.group());
        assertEquals(19.8, ev.tps1m());
        assertEquals(52.4, ev.msptAvg());
        assertEquals(17L, ev.gcCollections());
        assertEquals(230L, ev.gcTimeMs());
        assertEquals(3, ev.playerCount());
        assertEquals("Paper 1.21.4", ev.serverVersion());
        assertEquals(7, ev.pluginCount());
        assertNotNull(ev.worlds());
        assertEquals(2, ev.worlds().size());
        var first = ev.worlds().get(0);
        assertEquals("world", first.name());
        assertEquals("NORMAL", first.environment());
        assertEquals(120, first.entityCount());
        assertEquals(30, first.chunkCount());
        assertEquals(2, first.playerCount());
    }

    @Test
    @DisplayName("null worlds list is normalised to empty")
    void nullWorldsSafe() throws Exception {
        state.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));

        state.updateInstanceMetrics(new InstanceMetrics(
                "lobby-1",
                20.0,
                20.0,
                20.0,
                50.0,
                100L,
                1024L,
                512L,
                0L,
                0L,
                8,
                4,
                0,
                100,
                0,
                0L,
                0L,
                null,
                "test",
                0,
                1000L,
                Instant.now()));

        var ev = waitForLast();
        assertNotNull(ev.worlds());
        assertEquals(0, ev.worlds().size());
    }

    private InstanceMetricsUpdatedEvent waitForLast() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (received.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(!received.isEmpty(), "expected at least one event");
        return received.get(received.size() - 1);
    }
}
