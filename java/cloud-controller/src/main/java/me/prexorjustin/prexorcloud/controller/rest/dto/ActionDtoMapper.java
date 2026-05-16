package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.Map;

public final class ActionDtoMapper {

    private ActionDtoMapper() {}

    public static Map<String, Object> statusResponse(String status) {
        return Map.of("status", status);
    }

    public static Map<String, Object> statusCountResponse(String status, int count) {
        return Map.of("status", status, "count", count);
    }
}
