package me.prexorjustin.prexorcloud.controller.console;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConsoleBufferTest {

    @Test
    void truncatesLongLinesBeforeBuffering() {
        var buffer = new ConsoleBuffer(10, 32, 10, 1000);

        var result = buffer.append("lobby-1", "x".repeat(80), 100);

        assertEquals(1, result.acceptedLines().size());
        assertEquals(32, result.acceptedLines().getFirst().length());
        assertTrue(result.acceptedLines().getFirst().endsWith(" ... [truncated]"));
        assertEquals(result.acceptedLines(), buffer.getLines("lobby-1"));
    }

    @Test
    void rateLimitsWithinWindowAndEmitsSummaryOnNextWindow() {
        var buffer = new ConsoleBuffer(10, 128, 2, 1000);

        assertEquals(
                "line-1",
                buffer.append("lobby-1", "line-1", 100).acceptedLines().getFirst());
        assertEquals(
                "line-2",
                buffer.append("lobby-1", "line-2", 200).acceptedLines().getFirst());
        var dropped = buffer.append("lobby-1", "line-3", 300);
        assertTrue(dropped.acceptedLines().isEmpty());
        assertTrue(dropped.rateLimitedOrTruncated());
        assertEquals(1, dropped.suppressedCount());

        var resumed = buffer.append("lobby-1", "line-4", 1200);

        assertEquals(2, resumed.acceptedLines().size());
        assertEquals(
                "[prexorcloud] suppressed 1 console line due to rate limiting",
                resumed.acceptedLines().getFirst());
        assertEquals("line-4", resumed.acceptedLines().get(1));
        assertEquals(
                "[prexorcloud] suppressed 1 console line due to rate limiting",
                buffer.getLines("lobby-1").get(2));
    }

    @Test
    void evictClearsBufferedLinesAndRateWindow() {
        var buffer = new ConsoleBuffer(10, 128, 1, 1000);

        buffer.append("lobby-1", "line-1", 100);
        assertTrue(buffer.append("lobby-1", "line-2", 200).acceptedLines().isEmpty());
        buffer.evict("lobby-1");

        assertTrue(buffer.getLines("lobby-1").isEmpty());
        assertEquals(
                "line-3",
                buffer.append("lobby-1", "line-3", 300).acceptedLines().getFirst());
    }
}
