package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;

import me.prexorjustin.prexorcloud.security.token.JoinToken;
import me.prexorjustin.prexorcloud.security.token.JoinTokenStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TokenDtoMapper")
class TokenDtoMapperTest {

    @Test
    @DisplayName("maps token list and creation payloads")
    void mapsTokenDtos() {
        JoinToken token =
                new JoinToken("token-1", "node-a", "hash", "plain-join-token", Instant.parse("2099-01-01T00:00:00Z"));

        assertEquals("ACTIVE", TokenDtoMapper.toDto(token).get("status"));
        assertEquals(
                Map.of(
                        "tokenId",
                        "token-1",
                        "token",
                        "plain-join-token",
                        "nodeId",
                        "node-a",
                        "expiresAt",
                        "2099-01-01T00:00:00Z"),
                TokenDtoMapper.createResponse(new JoinTokenStore.JoinTokenResult(token, "plain-join-token"), "node-a"));
    }
}
