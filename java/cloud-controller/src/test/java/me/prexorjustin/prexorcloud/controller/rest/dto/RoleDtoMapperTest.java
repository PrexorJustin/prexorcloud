package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.auth.RoleConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RoleDtoMapper")
class RoleDtoMapperTest {

    @Test
    @DisplayName("maps role payload including assigned user count")
    void mapsRoleDto() {
        Map<String, Object> dto =
                RoleDtoMapper.toDto(new RoleConfig("MODERATOR", List.of("players.view", "players.transfer"), false), 3);

        assertEquals("MODERATOR", dto.get("name"));
        assertEquals(false, dto.get("builtIn"));
        assertEquals(3L, dto.get("userCount"));
    }
}
