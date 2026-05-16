package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.state.PlayerInfo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlayerDtoMapper")
class PlayerDtoMapperTest {

    @Test
    @DisplayName("maps the player dto contract")
    void mapsPlayerDto() {
        PlayerInfo player = new PlayerInfo(
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                "Notch",
                "lobby-a3f1",
                "lobby",
                "proxy-1",
                Instant.parse("2026-04-17T10:15:00Z"));

        Map<String, Object> dto = PlayerDtoMapper.toDto(player);

        assertEquals("lobby-a3f1", dto.get("currentInstance"));
        assertEquals("lobby", dto.get("currentGroup"));
        assertEquals("proxy-1", dto.get("proxyInstance"));
        assertEquals("2026-04-17T10:15:00Z", dto.get("connectedSince"));
    }
}
