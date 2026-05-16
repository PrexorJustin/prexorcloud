package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.crash.CrashRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CrashDtoMapper")
class CrashDtoMapperTest {

    @Test
    @DisplayName("maps crash summary and detail payloads")
    void mapsCrashPayloads() {
        CrashRecord crash = new CrashRecord(
                "crash-1",
                "lobby-1",
                "lobby",
                "node-a",
                137,
                "OOM",
                "OutOfMemoryError: Java heap space",
                "a1b2c3d4e5f6a7b8",
                List.of("line-1", "line-2"),
                120_000L,
                Instant.parse("2026-04-17T12:30:00Z"));

        Map<String, Object> summary = CrashDtoMapper.toSummaryDto(crash);
        Map<String, Object> detail = CrashDtoMapper.toDetailDto(crash);

        assertEquals("crash-1", summary.get("id"));
        assertEquals("lobby-1", summary.get("instanceId"));
        assertEquals("node-a", summary.get("node"));
        assertEquals("OutOfMemoryError: Java heap space", summary.get("causeSummary"));
        assertEquals("a1b2c3d4e5f6a7b8", summary.get("signature"));
        assertEquals("2026-04-17T12:30:00Z", summary.get("crashedAt"));
        assertEquals(List.of("line-1", "line-2"), detail.get("logTail"));
    }
}
