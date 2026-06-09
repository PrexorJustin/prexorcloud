package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.Map;

public final class CatalogDtoMapper {

    private CatalogDtoMapper() {}

    public static Map<String, Object> versionResponse(String platform, String version) {
        return Map.of("platform", platform, "version", version);
    }

    public static Map<String, Object> recommendedVersionResponse(String platform, String version) {
        return Map.of("platform", platform, "version", version, "recommended", true);
    }
}
