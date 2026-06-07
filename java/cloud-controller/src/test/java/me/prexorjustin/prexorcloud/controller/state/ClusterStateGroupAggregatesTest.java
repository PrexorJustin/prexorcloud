package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.event.events.GroupAggregatesUpdatedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClusterState — group aggregate emit")
class ClusterStateGroupAggregatesTest {

    private EventBus eventBus;
    private ClusterState state;
    private CopyOnWriteArrayList<GroupAggregatesUpdatedEvent> received;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        state = new ClusterState(eventBus);
        received = new CopyOnWriteArrayList<>();
        eventBus.subscribe(GroupAggregatesUpdatedEvent.class, received::add);
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    @DisplayName("addInstance fires event with running=1 totalPlayers=0")
    void firstInstanceEmits() throws Exception {
        state.addInstance(instance("lobby-1", "lobby"));
        var ev = waitForLast();
        assertEquals("lobby", ev.groupName());
        assertEquals(1, ev.runningInstances());
        assertEquals(0, ev.totalPlayers());
    }

    @Test
    @DisplayName("metrics-driven playerCount change fires updated totals without state change")
    void metricsPlayerCountFires() throws Exception {
        state.addInstance(instance("lobby-1", "lobby"));
        waitForCount(1);
        received.clear();

        state.updateInstanceMetrics(metricsWithPlayers("lobby-1", 7));
        var ev = waitForLast();
        assertEquals(1, ev.runningInstances());
        assertEquals(7, ev.totalPlayers());
    }

    @Test
    @DisplayName("repeated updates with the same totals do not emit again")
    void coalescesIdenticalTotals() throws Exception {
        state.addInstance(instance("lobby-1", "lobby"));
        waitForCount(1);
        received.clear();

        state.updateInstanceMetrics(metricsWithPlayers("lobby-1", 5));
        waitForCount(1);
        int after = received.size();

        // Re-applying identical playerCount should be a no-op for aggregates.
        state.updateInstanceMetrics(metricsWithPlayers("lobby-1", 5));
        Thread.sleep(150);
        assertEquals(after, received.size());
    }

    @Test
    @DisplayName("removeInstance fires final emit with running=0")
    void removeFiresZero() throws Exception {
        state.addInstance(instance("lobby-1", "lobby"));
        waitForCount(1);
        received.clear();

        state.removeInstance("lobby-1");
        var ev = waitForLast();
        assertEquals(0, ev.runningInstances());
        assertEquals(0, ev.totalPlayers());
    }

    @Test
    @DisplayName("removing an unknown instance does not spuriously emit")
    void unseenGroupSilent() throws Exception {
        state.removeInstance("ghost");
        Thread.sleep(150);
        assertEquals(0, received.size());
    }

    @Test
    @DisplayName("two instances in the same group sum their player counts")
    void totalsAcrossInstances() throws Exception {
        state.addInstance(instance("lobby-1", "lobby"));
        state.addInstance(instance("lobby-2", "lobby"));
        waitForCount(2);
        received.clear();

        state.updateInstanceMetrics(metricsWithPlayers("lobby-1", 3));
        state.updateInstanceMetrics(metricsWithPlayers("lobby-2", 4));
        Thread.sleep(200);

        var last = received.get(received.size() - 1);
        assertEquals(2, last.runningInstances());
        assertEquals(7, last.totalPlayers());
    }

    private GroupAggregatesUpdatedEvent waitForLast() throws InterruptedException {
        waitForCount(1);
        return received.get(received.size() - 1);
    }

    private void waitForCount(int min) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (received.size() < min && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(received.size() >= min, "expected at least " + min + " events, got " + received.size());
    }

    private static InstanceInfo instance(String id, String group) {
        return new InstanceInfo(id, group, "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now());
    }

    private static InstanceMetrics metricsWithPlayers(String instanceId, int playerCount) {
        return new InstanceMetrics(
                instanceId,
                20.0,
                20.0,
                20.0, // tps1m, tps5m, tps15m
                50.0, // msptAvg
                100L,
                1024L,
                512L, // heapUsed/Max/Committed
                0L,
                0L, // gcCollections, gcTimeMs
                8,
                4, // thread/daemon count
                playerCount,
                100, // playerCount, maxPlayers
                1,
                0L,
                0L, // worldCount, totalEntities, totalChunks
                List.of(), // worlds
                "test", // serverVersion
                0, // pluginCount
                1000L, // uptimeMs
                Instant.now());
    }
}
