package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.state.NodeHostInfo;
import me.prexorjustin.prexorcloud.controller.state.NodeState;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.security.token.JoinToken;

public final class NodeDtoMapper {

    private NodeDtoMapper() {}

    public static Map<String, Object> toConnectedDto(NodeState node, StateStore.RegisteredNode registeredNode) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", node.nodeId());
        dto.put("address", node.address());
        dto.put("type", "CONNECTED");
        dto.put("status", node.status().name());
        dto.put("cpuUsage", node.cpuUsage());
        dto.put("totalMemoryMb", node.totalMemoryMb());
        dto.put("usedMemoryMb", node.usedMemoryMb());
        dto.put("freeDiskMb", node.freeDiskMb());
        dto.put("totalDiskMb", node.totalDiskMb());
        dto.put("instanceCount", node.instanceCount());
        dto.put("connectedSince", node.connectedSince().toString());
        dto.put("lastHeartbeat", node.lastHeartbeat().toString());
        dto.put("labels", node.labels());
        dto.put("hostInfo", toHostInfoDto(node.hostInfo()));
        if (registeredNode != null) {
            dto.put("firstSeen", registeredNode.firstSeen().toString());
            dto.put("lastSeen", registeredNode.lastSeen().toString());
        }
        return dto;
    }

    public static Map<String, Object> toDisconnectedDto(StateStore.RegisteredNode registeredNode) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", registeredNode.nodeId());
        dto.put("type", "DISCONNECTED");
        dto.put("status", "OFFLINE");
        dto.put("firstSeen", registeredNode.firstSeen().toString());
        dto.put("lastSeen", registeredNode.lastSeen().toString());
        return dto;
    }

    public static Map<String, Object> toPendingDto(JoinToken token) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", token.nodeId());
        dto.put("type", "PENDING");
        dto.put("status", "PENDING");
        dto.put("tokenId", token.tokenId());
        dto.put("joinToken", token.plainToken());
        dto.put("expiresAt", token.expiresAt().toString());
        return dto;
    }

    public static Map<String, Object> nodeStatusDto(String status) {
        return Map.of("status", status);
    }

    public static Map<String, Object> nodeDrainDto(
            String status, boolean shutdown, int drainTimeoutSeconds, String kickMessage) {
        return Map.of(
                "status", status,
                "shutdown", shutdown,
                "drainTimeoutSeconds", drainTimeoutSeconds,
                "kickMessage", kickMessage);
    }

    private static Map<String, Object> toHostInfoDto(NodeHostInfo hostInfo) {
        if (hostInfo == null || NodeHostInfo.UNKNOWN.equals(hostInfo) || "unknown".equals(hostInfo.osName())) {
            return null;
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("osName", hostInfo.osName());
        dto.put("osVersion", hostInfo.osVersion());
        dto.put("arch", hostInfo.arch());
        dto.put("cpuModel", hostInfo.cpuModel());
        dto.put("cpuPhysicalCores", hostInfo.cpuPhysicalCores());
        dto.put("cpuLogicalCores", hostInfo.cpuLogicalCores());
        dto.put("cpuMaxFreqHz", hostInfo.cpuMaxFreqHz());
        dto.put("javaVersion", hostInfo.javaVersion());
        dto.put("javaVendor", hostInfo.javaVendor());
        dto.put("javaRuntime", hostInfo.javaRuntime());
        dto.put("javaGc", hostInfo.javaGc());
        return dto;
    }
}
