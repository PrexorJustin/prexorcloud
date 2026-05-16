package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.HashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.auth.RoleConfig;

public final class RoleDtoMapper {

    private RoleDtoMapper() {}

    public static Map<String, Object> toDto(RoleConfig role, long userCount) {
        var dto = new HashMap<String, Object>();
        dto.put("name", role.name());
        dto.put("permissions", role.permissions());
        dto.put("builtIn", role.builtIn());
        dto.put("userCount", userCount);
        return dto;
    }
}
