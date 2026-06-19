package me.prexorjustin.prexorcloud.daemon.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.prexorjustin.prexorcloud.protocol.CrashReport;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the bounded crash-report replay buffer (Phase 3): FIFO drain, oldest-dropped when
 * full, and null-safety.
 */
final class CrashReportBufferTest {

    private static CrashReport report(String id) {
        return CrashReport.newBuilder().setInstanceId(id).setGroup("g").build();
    }

    private static List<String> ids(List<CrashReport> reports) {
        return reports.stream().map(CrashReport::getInstanceId).toList();
    }

    @Test
    void drainsInFifoOrderAndEmpties() {
        var buffer = new CrashReportBuffer(8);
        buffer.add(report("a"));
        buffer.add(report("b"));
        buffer.add(report("c"));
        assertEquals(3, buffer.size());

        assertEquals(List.of("a", "b", "c"), ids(buffer.drainAll()), "drain returns FIFO order");
        assertEquals(0, buffer.size(), "drain empties the buffer");
        assertTrue(buffer.drainAll().isEmpty(), "draining an empty buffer yields nothing");
    }

    @Test
    void dropsOldestWhenFull() {
        var buffer = new CrashReportBuffer(3);
        buffer.add(report("1"));
        buffer.add(report("2"));
        buffer.add(report("3"));
        buffer.add(report("4")); // evicts "1"
        buffer.add(report("5")); // evicts "2"

        assertEquals(3, buffer.size());
        assertEquals(2, buffer.droppedCount());
        assertEquals(List.of("3", "4", "5"), ids(buffer.drainAll()), "the newest reports survive eviction");
    }

    @Test
    void ignoresNull() {
        var buffer = new CrashReportBuffer(4);
        buffer.add(null);
        assertEquals(0, buffer.size());
    }
}
