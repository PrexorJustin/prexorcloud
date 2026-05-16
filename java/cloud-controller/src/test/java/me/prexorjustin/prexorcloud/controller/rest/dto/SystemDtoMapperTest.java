package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import me.prexorjustin.prexorcloud.common.util.VersionInfo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SystemDtoMapper")
class SystemDtoMapperTest {

    @Test
    @DisplayName("maps version and system settings payloads")
    void mapsSystemPayloads() {
        Map<String, Object> versionDto = SystemDtoMapper.versionDto(new VersionInfo("1.2.0", "abc1234", "21.0.2"));
        Map<String, Object> settingsDto = SystemDtoMapper.settingsDto(3, 12, 247, 5, 10_000L, true, true);

        assertEquals("1.2.0", versionDto.get("version"));
        assertEquals("abc1234", versionDto.get("gitCommit"));
        assertEquals(3, settingsDto.get("nodeCount"));
        assertEquals(10_000L, settingsDto.get("heartbeatInterval"));
        assertEquals(true, settingsDto.get("metricsEnabled"));
        assertEquals(true, settingsDto.get("shareEnabled"));
    }
}
