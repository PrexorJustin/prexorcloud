package me.prexorjustin.prexorcloud.controller.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogFilter;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogRecord;

import org.junit.jupiter.api.Test;

class DaemonLogStoreTest {

    @Test
    void perNodeRingsAreIndependent() {
        var store = new DaemonLogStore(3);

        for (int i = 0; i < 5; i++) {
            store.append("node-a", i, "INFO", "x", "t", "a-" + i, null, Map.of());
        }
        store.append("node-b", 100, "INFO", "x", "t", "b-only", null, Map.of());

        List<LogRecord> a = store.recent("node-a", LogFilter.accept(), 10);
        List<LogRecord> b = store.recent("node-b", LogFilter.accept(), 10);

        assertEquals(3, a.size(), "ring trims to capacity per-node");
        assertEquals("a-2", a.get(0).message());
        assertEquals("a-4", a.get(2).message());
        assertEquals(1, b.size(), "node-b is unaffected by node-a's volume");
        assertEquals("b-only", b.get(0).message());

        assertEquals(3, store.size("node-a"));
        assertEquals(1, store.size("node-b"));
    }

    @Test
    void unknownNodeReturnsEmpty() {
        var store = new DaemonLogStore();
        assertTrue(store.recent("never-seen", LogFilter.accept(), 100).isEmpty());
        assertEquals(0, store.size("never-seen"));
    }

    @Test
    void subscribeFiresOnSameNodeOnly() {
        var store = new DaemonLogStore(10);
        AtomicInteger aDeliveries = new AtomicInteger();
        AtomicInteger bDeliveries = new AtomicInteger();

        try (var subA = store.subscribe("node-a", record -> aDeliveries.incrementAndGet());
                var subB = store.subscribe("node-b", record -> bDeliveries.incrementAndGet())) {
            store.append("node-a", 1, "INFO", "x", "t", "a", null, Map.of());
            store.append("node-a", 2, "WARN", "x", "t", "a2", null, Map.of());
            store.append("node-b", 3, "INFO", "x", "t", "b", null, Map.of());

            assertEquals(2, aDeliveries.get());
            assertEquals(1, bDeliveries.get());
        }
    }

    @Test
    void removeDropsBuffer() {
        var store = new DaemonLogStore(10);
        store.append("node-a", 1, "INFO", "x", "t", "msg", null, Map.of());
        assertEquals(1, store.size("node-a"));

        store.remove("node-a");
        assertEquals(0, store.size("node-a"));
        assertTrue(store.recent("node-a", LogFilter.accept(), 10).isEmpty());
    }

    @Test
    void filterMatchesOnLevelAndLogger() {
        var store = new DaemonLogStore(10);
        store.append("node", 1, "DEBUG", "a.b.X", "t", "debug", null, Map.of());
        store.append("node", 2, "INFO", "a.b.X", "t", "info", null, Map.of());
        store.append("node", 3, "WARN", "other.Y", "t", "warn-other", null, Map.of());
        store.append("node", 4, "ERROR", "a.b.X", "t", "error", null, Map.of());

        List<LogRecord> warnAndAbove = store.recent("node", LogFilter.atLeast("WARN", null), 10);
        assertEquals(2, warnAndAbove.size());
        assertEquals("warn-other", warnAndAbove.get(0).message());
        assertEquals("error", warnAndAbove.get(1).message());

        List<LogRecord> ab = store.recent("node", LogFilter.atLeast("INFO", "a.b."), 10);
        assertEquals(2, ab.size());
        assertEquals("info", ab.get(0).message());
        assertEquals("error", ab.get(1).message());
    }
}
