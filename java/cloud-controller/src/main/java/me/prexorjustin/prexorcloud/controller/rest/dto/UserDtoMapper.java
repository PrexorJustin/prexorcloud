package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.auth.Role;
import me.prexorjustin.prexorcloud.controller.auth.User;

public final class UserDtoMapper {

    private UserDtoMapper() {}

    public static Map<String, Object> toDto(User user) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("username", user.username());
        dto.put("role", user.role());
        dto.put("permissions", List.copyOf(Role.permissionsFor(user.role())));
        dto.put("avatarUrl", user.avatarPath() != null ? avatarUrl(user.username()) : null);
        dto.put("minecraftUuid", user.minecraftUuid());
        dto.put("minecraftName", user.minecraftName());
        dto.put("createdAt", user.createdAt());
        return dto;
    }

    public static Map<String, Object> avatarResponse(String username) {
        return Map.of("avatarUrl", avatarUrl(username));
    }

    private static String avatarUrl(String username) {
        return "/api/v1/users/" + username + "/avatar";
    }
}
