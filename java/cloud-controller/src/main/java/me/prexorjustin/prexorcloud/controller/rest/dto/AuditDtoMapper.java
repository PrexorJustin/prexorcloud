package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.state.StateStore.AuditEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Maps audit entries to the wire shape. {@code details} is kept as the
 * free-form JSON string the rest of the system stores; {@code before} /
 * {@code after} are parsed back into native JSON so dashboards can render a
 * structural diff without double-decoding. Malformed snapshots fall back to a
 * raw string so a bad row never blocks the listing.
 */
public final class AuditDtoMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditDtoMapper() {}

    public static Map<String, Object> toDto(AuditEntry entry) {
        var dto = new LinkedHashMap<String, Object>();
        dto.put("id", entry.id());
        dto.put("username", entry.username());
        dto.put("action", entry.action());
        dto.put("resourceType", entry.resourceType());
        dto.put("resourceId", entry.resourceId());
        dto.put("details", entry.details());
        dto.put("before", parseOrNull(entry.beforeJson()));
        dto.put("after", parseOrNull(entry.afterJson()));
        dto.put("ipAddress", entry.ipAddress());
        dto.put("createdAt", entry.createdAt());
        return dto;
    }

    private static JsonNode parseOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.getNodeFactory().textNode(json);
        }
    }
}
