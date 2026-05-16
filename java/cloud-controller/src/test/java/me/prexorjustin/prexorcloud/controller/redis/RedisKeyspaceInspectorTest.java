package me.prexorjustin.prexorcloud.controller.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RedisKeyspaceInspectorTest {

    @Test
    void reportsCountsAndBoundedSamplesPerPrefix() {
        var inspector = new RedisKeyspaceInspector(prefix -> Map.of(
                        RedisKeys.SSE_PREFIX,
                        List.of(
                                RedisKeys.SSE_SEQUENCE,
                                RedisKeys.SSE_REPLAY,
                                RedisKeys.sseTicket("a"),
                                RedisKeys.sseTicket("b"),
                                RedisKeys.sseTicket("c"),
                                RedisKeys.sseTicket("d")),
                        RedisKeys.LEASE_PREFIX,
                        List.of(RedisKeys.lease("group:lobby")))
                .getOrDefault(prefix, List.of()));

        var report = inspector.inspect(List.of(RedisKeys.SSE_PREFIX, RedisKeys.LEASE_PREFIX, "prexor:v1:missing:"));

        assertEquals(true, report.available());
        assertEquals(7, report.totalKeys());
        assertNull(report.error());
        assertEquals(RedisKeys.LEASE_PREFIX, report.prefixes().get(0).prefix());
        assertEquals(1, report.prefixes().get(0).keyCount());
        assertEquals(RedisKeys.SSE_PREFIX, report.prefixes().get(2).prefix());
        assertEquals(6, report.prefixes().get(2).keyCount());
        assertEquals(5, report.prefixes().get(2).sampleKeys().size());
    }

    @Test
    void reportsUnavailableWhenScanFails() {
        var inspector = new RedisKeyspaceInspector(prefix -> {
            throw new IllegalStateException("redis unavailable");
        });

        var report = inspector.inspect(List.of(RedisKeys.SSE_PREFIX));

        assertFalse(report.available());
        assertEquals(0, report.totalKeys());
        assertEquals("redis unavailable", report.error());
    }
}
