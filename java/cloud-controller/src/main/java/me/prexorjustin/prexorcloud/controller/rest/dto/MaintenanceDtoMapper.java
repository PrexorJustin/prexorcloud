package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.Map;

import me.prexorjustin.prexorcloud.controller.config.MaintenanceConfig;

public final class MaintenanceDtoMapper {

    private MaintenanceDtoMapper() {}

    public static Map<String, Object> toDto(MaintenanceConfig config) {
        return Map.of("enabled", config.enabled(), "message", config.message());
    }
}
