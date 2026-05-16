package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.auth.User;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserDtoMapper")
class UserDtoMapperTest {

    @Test
    @DisplayName("maps the full user dto contract")
    void mapsUserDto() {
        User user = new User(
                "operator1",
                "hash",
                "ADMIN",
                "data/avatars/operator1.png",
                "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                "Notch",
                "2026-04-01T12:00:00Z");

        Map<String, Object> dto = UserDtoMapper.toDto(user);

        assertEquals("operator1", dto.get("username"));
        assertEquals("ADMIN", dto.get("role"));
        assertEquals("2026-04-01T12:00:00Z", dto.get("createdAt"));
        assertEquals("/api/v1/users/operator1/avatar", dto.get("avatarUrl"));
        assertEquals("Notch", dto.get("minecraftName"));
        assertTrue(((List<?>) dto.get("permissions")).contains("users.view"));
        assertEquals(Map.of("avatarUrl", "/api/v1/users/operator1/avatar"), UserDtoMapper.avatarResponse("operator1"));
    }
}
