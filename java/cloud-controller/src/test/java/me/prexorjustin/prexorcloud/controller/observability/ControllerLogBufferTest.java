package me.prexorjustin.prexorcloud.controller.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogFilter;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogRecord;

import org.junit.jupiter.api.Test;

class ControllerLogBufferTest {

    @Test
    void monotonicSequenceAndRingTrim() {
        var buffer = new ControllerLogBuffer(3);

        buffer.append(1L, "INFO", "x.y.A", "main", "first", null, null);
        buffer.append(2L, "INFO", "x.y.A", "main", "second", null, null);
        buffer.append(3L, "INFO", "x.y.A", "main", "third", null, null);
        buffer.append(4L, "INFO", "x.y.A", "main", "fourth", null, null);

        List<LogRecord> recent = buffer.recent(LogFilter.accept(), 10);
        assertEquals(3, recent.size());
        assertEquals("second", recent.get(0).message());
        assertEquals("third", recent.get(1).message());
        assertEquals("fourth", recent.get(2).message());
        assertEquals(2L, recent.get(0).sequence());
        assertEquals(3L, recent.get(1).sequence());
        assertEquals(4L, recent.get(2).sequence());
    }

    @Test
    void recentRespectsLimitAndOldestFirst() {
        var buffer = new ControllerLogBuffer(10);
        for (int i = 0; i < 5; i++) {
            buffer.append(i, "INFO", "x", "t", "msg-" + i, null, Map.of());
        }

        List<LogRecord> recent = buffer.recent(LogFilter.accept(), 2);
        assertEquals(2, recent.size());
        assertEquals("msg-3", recent.get(0).message());
        assertEquals("msg-4", recent.get(1).message());
    }

    @Test
    void filterByMinLevel() {
        var buffer = new ControllerLogBuffer(10);
        buffer.append(1L, "DEBUG", "x", "t", "debug", null, null);
        buffer.append(2L, "INFO", "x", "t", "info", null, null);
        buffer.append(3L, "WARN", "x", "t", "warn", null, null);
        buffer.append(4L, "ERROR", "x", "t", "error", null, null);

        var warnAndAbove = buffer.recent(LogFilter.atLeast("WARN", null), 10);
        assertEquals(2, warnAndAbove.size());
        assertEquals("warn", warnAndAbove.get(0).message());
        assertEquals("error", warnAndAbove.get(1).message());
    }

    @Test
    void filterByLoggerPrefix() {
        var buffer = new ControllerLogBuffer(10);
        buffer.append(1L, "INFO", "me.prexorjustin.prexorcloud.controller.A", "t", "ctrl", null, null);
        buffer.append(2L, "INFO", "me.prexorjustin.prexorcloud.daemon.B", "t", "daemon", null, null);

        var ctrlOnly = buffer.recent(LogFilter.atLeast("INFO", "me.prexorjustin.prexorcloud.controller."), 10);
        assertEquals(1, ctrlOnly.size());
        assertEquals("ctrl", ctrlOnly.getFirst().message());
    }

    @Test
    void subscriberSeesNewRecordsAndUnsubscribeCleansUp() {
        var buffer = new ControllerLogBuffer(10);
        var seen = new ArrayList<LogRecord>();

        var subscription = buffer.subscribe(seen::add);
        buffer.append(1L, "INFO", "x", "t", "first", null, null);
        buffer.append(2L, "INFO", "x", "t", "second", null, null);
        subscription.close();
        buffer.append(3L, "INFO", "x", "t", "third", null, null);

        assertEquals(2, seen.size());
        assertEquals("first", seen.get(0).message());
        assertEquals("second", seen.get(1).message());
    }

    @Test
    void throwingSubscriberIsRemovedAutomatically() {
        var buffer = new ControllerLogBuffer(10);
        var seen = new ArrayList<LogRecord>();
        buffer.subscribe(r -> {
            throw new RuntimeException("bad listener");
        });
        buffer.subscribe(seen::add);

        // First append removes the bad listener; second append must not double-throw.
        buffer.append(1L, "INFO", "x", "t", "first", null, null);
        buffer.append(2L, "INFO", "x", "t", "second", null, null);

        assertEquals(2, seen.size());
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ControllerLogBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new ControllerLogBuffer(-1));
    }
}
