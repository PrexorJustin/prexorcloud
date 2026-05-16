package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.prexorjustin.prexorcloud.controller.state.StateStore.AuditEntry;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AuditDtoMapper")
class AuditDtoMapperTest {

    @Test
    @DisplayName("parses before/after JSON strings into native nodes")
    void parsesBeforeAfter() {
        var entry = new AuditEntry(
                1L,
                "admin",
                "group.update",
                "group",
                "lobby",
                "{}",
                "{\"minInstances\":1}",
                "{\"minInstances\":3}",
                "127.0.0.1",
                "2026-05-11T12:00:00Z");

        var dto = AuditDtoMapper.toDto(entry);

        var before = (JsonNode) dto.get("before");
        var after = (JsonNode) dto.get("after");
        assertNotNull(before);
        assertNotNull(after);
        assertEquals(1, before.get("minInstances").asInt());
        assertEquals(3, after.get("minInstances").asInt());
        assertEquals("{}", dto.get("details"));
    }

    @Test
    @DisplayName("nulls out before/after when entry has no snapshots")
    void omitsMissingSnapshots() {
        var entry =
                new AuditEntry(2L, "admin", "user.login", "user", "admin", "{}", "127.0.0.1", "2026-05-11T12:00:00Z");

        var dto = AuditDtoMapper.toDto(entry);

        assertNull(dto.get("before"));
        assertNull(dto.get("after"));
    }

    @Test
    @DisplayName("falls back to text node when stored JSON is malformed")
    void fallsBackOnMalformedJson() {
        var entry = new AuditEntry(
                3L,
                "admin",
                "group.update",
                "group",
                "lobby",
                "{}",
                "not-json",
                null,
                "127.0.0.1",
                "2026-05-11T12:00:00Z");

        var dto = AuditDtoMapper.toDto(entry);
        var before = (JsonNode) dto.get("before");
        assertNotNull(before);
        assertTrue(before.isTextual());
        assertEquals("not-json", before.asText());
        assertNull(dto.get("after"));
    }
}
