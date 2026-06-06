package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.PlayerInfo;

public final class WorkloadDtoMapper {

    private WorkloadDtoMapper() {}

    public static Map<String, Object> tokenRefreshResponse(String token) {
        return Map.of("token", token);
    }

    public static Map<String, Object> statusResponse(String status) {
        return Map.of("status", status);
    }

    public static Map<String, Object> messageQueuedResponse(String id) {
        return Map.of("id", id, "status", "QUEUED");
    }

    public static Map<String, Object> transferQueuedResponse(UUID playerUuid, String targetInstanceId) {
        return Map.of(
                "status", "transfer_queued", "playerId", playerUuid.toString(), "targetInstanceId", targetInstanceId);
    }

    public static Map<String, Object> toInstanceDto(InstanceInfo instance, String nodeAddress) {
        var dto = new LinkedHashMap<String, Object>();
        dto.put("instanceId", instance.id());
        dto.put("group", instance.group());
        dto.put("nodeId", instance.nodeId());
        dto.put("nodeAddress", nodeAddress);
        dto.put("state", instance.state().name());
        dto.put("port", instance.port());
        dto.put("playerCount", instance.playerCount());
        dto.put("uptimeMs", instance.uptimeMs());
        return dto;
    }

    public static Map<String, Object> toGroupDto(GroupConfig group, long onlineCount) {
        var dto = new LinkedHashMap<String, Object>();
        dto.put("name", group.name());
        dto.put("platform", group.platform());
        dto.put("minInstances", group.minInstances());
        dto.put("maxInstances", group.maxInstances());
        dto.put("maxPlayers", group.maxPlayers());
        dto.put("onlineCount", (int) onlineCount);
        dto.put("isMaintenance", group.maintenance());
        dto.put("maintenanceMessage", group.maintenanceMessage());
        dto.put("maintenanceBypass", group.maintenanceBypass());
        dto.put("isStatic", group.isStatic());
        dto.put("defaultGroup", group.defaultGroup());
        dto.put("memoryMb", group.memoryMb());
        dto.put("cpuReservation", group.cpuReservation());
        dto.put("diskReservationMb", group.diskReservationMb());
        dto.put("jvmArgs", group.jvmArgs());
        dto.put("env", group.env());
        dto.put("nodeAffinity", group.nodeAffinity());
        return dto;
    }

    public static Map<String, Object> toPlayerDto(PlayerInfo player) {
        var dto = new LinkedHashMap<String, Object>();
        dto.put("id", player.uuid().toString());
        dto.put("name", player.name());
        dto.put("instanceId", player.instanceId());
        dto.put("group", player.group());
        dto.put("edition", player.edition());
        return dto;
    }

    public static Map<String, Object> toPendingTransferDto(
            UUID playerUuid, InstanceInfo targetInstance, String nodeAddress) {
        var dto = new LinkedHashMap<String, Object>();
        dto.put("playerUuid", playerUuid.toString());
        dto.put("targetInstanceId", targetInstance.id());
        dto.put("nodeId", targetInstance.nodeId());
        dto.put("nodeAddress", nodeAddress);
        dto.put("port", targetInstance.port());
        return dto;
    }
}
