package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import me.prexorjustin.prexorcloud.controller.config.MaintenanceConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MaintenanceDtoMapper")
class MaintenanceDtoMapperTest {

    @Test
    @DisplayName("maps maintenance config payload")
    void mapsMaintenancePayload() {
        Map<String, Object> dto = MaintenanceDtoMapper.toDto(new MaintenanceConfig(true, "Down for maintenance"));

        assertEquals(true, dto.get("enabled"));
        assertEquals("Down for maintenance", dto.get("message"));
    }
}
