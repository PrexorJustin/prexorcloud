package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.Map;

import me.prexorjustin.prexorcloud.security.token.JoinToken;
import me.prexorjustin.prexorcloud.security.token.JoinTokenStore;

public final class TokenDtoMapper {

    private TokenDtoMapper() {}

    public static Map<String, Object> toDto(JoinToken token) {
        return Map.of(
                "tokenId",
                token.tokenId(),
                "nodeId",
                token.nodeId(),
                "expiresAt",
                token.expiresAt().toString(),
                "status",
                token.isExpired() ? "EXPIRED" : "ACTIVE");
    }

    public static Map<String, Object> createResponse(JoinTokenStore.JoinTokenResult result, String nodeId) {
        return Map.of(
                "tokenId",
                result.token().tokenId(),
                "token",
                result.plaintextToken(),
                "nodeId",
                nodeId,
                "expiresAt",
                result.token().expiresAt().toString());
    }
}
