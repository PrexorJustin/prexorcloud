package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.time.Instant;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.auth.User;

public final class AuthDtoMapper {

    private AuthDtoMapper() {}

    public static Map<String, Object> loginResponse(String token, User user) {
        return Map.of("token", token, "user", UserDtoMapper.toDto(user));
    }

    public static Map<String, Object> tokenResponse(String token) {
        return Map.of("token", token);
    }

    public static Map<String, Object> statusResponse(String status) {
        return Map.of("status", status);
    }

    public static Map<String, Object> lockedResponse(Instant lockedUntil) {
        return Map.of(
                "error",
                "ACCOUNT_LOCKED",
                "message",
                "Too many failed login attempts. Try again later.",
                "lockedUntil",
                lockedUntil.toString());
    }
}
