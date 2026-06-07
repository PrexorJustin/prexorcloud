package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InstanceDtoMapper")
class InstanceDtoMapperTest {

    @Test
    @DisplayName("maps instance payload")
    void mapsInstanceDto() {
        Instant startedAt = Instant.parse("2026-04-10T10:15:30Z");
        Map<String, Object> dto = InstanceDtoMapper.toDto(new InstanceInfo(
                "survival-1", "survival", "node-a", InstanceState.RUNNING, 25566, 12, 654321L, startedAt, 7));

        assertEquals("survival-1", dto.get("id"));
        assertEquals("survival", dto.get("group"));
        assertEquals("node-a", dto.get("node"));
        assertEquals("RUNNING", dto.get("state"));
        assertEquals(25566, dto.get("port"));
        assertEquals(12, dto.get("playerCount"));
        assertEquals(654321L, dto.get("uptimeMs"));
        assertEquals(startedAt, dto.get("startedAt"));
        assertEquals(7, dto.get("deploymentRevision"));
    }
}
