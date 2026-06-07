package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.state.TemplateVersion;
import me.prexorjustin.prexorcloud.controller.template.TemplateConfig;

public final class TemplateDtoMapper {

    private TemplateDtoMapper() {}

    public static Map<String, Object> toDto(TemplateConfig template) {
        var dto = new LinkedHashMap<String, Object>();
        dto.put("name", template.name());
        dto.put("description", template.description());
        dto.put("platform", template.platform());
        dto.put("hash", template.hash());
        dto.put("sizeBytes", template.sizeBytes());
        return dto;
    }

    public static Map<String, Object> toVersionDto(TemplateVersion version) {
        return Map.of(
                "templateName",
                version.templateName(),
                "hash",
                version.hash(),
                "sizeBytes",
                version.sizeBytes(),
                "createdAt",
                version.createdAt());
    }

    public static Map<String, Object> statusResponse(String status) {
        return Map.of("status", status);
    }

    public static Map<String, Object> statusWithFilesResponse(String status, int files) {
        return Map.of("status", status, "files", files);
    }

    public static Map<String, Object> rollbackResponse(String hash) {
        return Map.of("status", "restored", "hash", hash);
    }
}
