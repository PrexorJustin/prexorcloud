package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.domain.PlayerEdition;
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
                Instant.parse("2026-04-17T10:15:00Z"),
                PlayerEdition.JAVA);

        Map<String, Object> dto = PlayerDtoMapper.toDto(player);

        assertEquals("lobby-a3f1", dto.get("currentInstance"));
        assertEquals("lobby", dto.get("currentGroup"));
        assertEquals("proxy-1", dto.get("proxyInstance"));
        assertEquals("2026-04-17T10:15:00Z", dto.get("connectedSince"));
        assertEquals("java", dto.get("edition"));
    }

    @Test
    @DisplayName("edition is derived from a Floodgate-shaped UUID when not supplied")
    void derivesBedrockEditionFromUuid() {
        // Floodgate UUID: high 64 bits zero. Passing a blank edition exercises the
        // PlayerInfo compact-constructor derivation (also the Jackson-rehydration path).
        PlayerInfo bedrock = new PlayerInfo(
                new UUID(0L, 12345L), "BedrockSteve", "lobby-a3f1", "lobby", "proxy-1", Instant.EPOCH, "");

        Map<String, Object> dto = PlayerDtoMapper.toDto(bedrock);

        assertEquals("bedrock", dto.get("edition"));
    }
}
