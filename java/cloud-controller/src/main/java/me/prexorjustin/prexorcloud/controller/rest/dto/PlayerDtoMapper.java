package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.state.PlayerInfo;

public final class PlayerDtoMapper {

    private PlayerDtoMapper() {}

    public static Map<String, Object> toDto(PlayerInfo player) {
        var dto = new LinkedHashMap<String, Object>();
        dto.put("id", player.uuid().toString());
        dto.put("name", player.name());
        dto.put("currentInstance", player.instanceId());
        dto.put("currentGroup", player.group());
        dto.put("proxyInstance", player.proxyInstanceId());
        dto.put("connectedSince", player.connectedAt().toString());
        return dto;
    }
}
