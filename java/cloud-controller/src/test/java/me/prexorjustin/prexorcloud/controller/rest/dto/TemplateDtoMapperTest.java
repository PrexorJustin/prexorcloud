package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import me.prexorjustin.prexorcloud.controller.state.TemplateVersion;
import me.prexorjustin.prexorcloud.controller.template.TemplateConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TemplateDtoMapper")
class TemplateDtoMapperTest {

    @Test
    @DisplayName("maps template and template-version payloads")
    void mapsTemplatePayloads() {
        TemplateConfig template = new TemplateConfig("global", "Shared configuration", "PAPER", "sha256:abc123", 2048);
        TemplateVersion version = new TemplateVersion("global", "sha256:def456", 1024, "2026-04-17T10:00:00Z");

        Map<String, Object> templateDto = TemplateDtoMapper.toDto(template);
        Map<String, Object> versionDto = TemplateDtoMapper.toVersionDto(version);

        assertEquals("global", templateDto.get("name"));
        assertEquals("PAPER", templateDto.get("platform"));
        assertEquals(2048L, templateDto.get("sizeBytes"));
        assertEquals("sha256:def456", versionDto.get("hash"));
        assertEquals("2026-04-17T10:00:00Z", versionDto.get("createdAt"));
    }

    @Test
    @DisplayName("maps template action envelopes")
    void mapsTemplateActionPayloads() {
        assertEquals(Map.of("status", "saved"), TemplateDtoMapper.statusResponse("saved"));
        assertEquals(
                Map.of("status", "uploaded", "files", 3), TemplateDtoMapper.statusWithFilesResponse("uploaded", 3));
        assertEquals(
                Map.of("status", "restored", "hash", "sha256:abc123"),
                TemplateDtoMapper.rollbackResponse("sha256:abc123"));
    }
}
