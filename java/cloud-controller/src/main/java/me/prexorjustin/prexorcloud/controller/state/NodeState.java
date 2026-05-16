package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record NodeState(
        String nodeId,
        String address,
        NodeStatus status,
        double cpuUsage,
        long totalMemoryMb,
        long usedMemoryMb,
        long freeDiskMb,
        long totalDiskMb,
        int instanceCount,
        Set<Integer> usedPorts,
        Map<String, String> labels,
        Instant connectedSince,
        Instant lastHeartbeat,
        NodeHostInfo hostInfo) {

    public enum NodeStatus {
        ONLINE,
        DRAINING,
        CORDONED,
        UNREACHABLE
    }

    public NodeState {
        if (address == null) address = "";
        if (labels == null) labels = Map.of();
        if (lastHeartbeat == null) lastHeartbeat = connectedSince;
        if (hostInfo == null) hostInfo = NodeHostInfo.UNKNOWN;
    }

    public long freeMemoryMb() {
        return totalMemoryMb - usedMemoryMb;
    }

    public NodeState withStatus(NodeStatus newStatus) {
        return new NodeState(
                nodeId,
                address,
                newStatus,
                cpuUsage,
                totalMemoryMb,
                usedMemoryMb,
                freeDiskMb,
                totalDiskMb,
                instanceCount,
                usedPorts,
                labels,
                connectedSince,
                lastHeartbeat,
                hostInfo);
    }

    public NodeState withResourceUpdate(
            double cpuUsage,
            long totalMemoryMb,
            long usedMemoryMb,
            long freeDiskMb,
            long totalDiskMb,
            int instanceCount,
            Set<Integer> usedPorts) {
        return new NodeState(
                nodeId,
                address,
                status,
                cpuUsage,
                totalMemoryMb,
                usedMemoryMb,
                freeDiskMb,
                totalDiskMb,
                instanceCount,
                usedPorts,
                labels,
                connectedSince,
                lastHeartbeat,
                hostInfo);
    }

    public NodeState withHeartbeat(Instant timestamp) {
        return new NodeState(
                nodeId,
                address,
                status,
                cpuUsage,
                totalMemoryMb,
                usedMemoryMb,
                freeDiskMb,
                totalDiskMb,
                instanceCount,
                usedPorts,
                labels,
                connectedSince,
                timestamp,
                hostInfo);
    }
}
