package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NodeRegistry {

    private final Map<String, NodeState> nodes = new ConcurrentHashMap<>();
    private final Map<String, NodeCacheStatus> cacheStatuses = new ConcurrentHashMap<>();

    public void hydrate(Map<String, NodeState> snapshot) {
        nodes.clear();
        cacheStatuses.clear();
        nodes.putAll(snapshot);
    }

    public NodeState add(
            String nodeId,
            String address,
            long totalMemoryMb,
            Map<String, String> labels,
            Instant connectedSince,
            NodeHostInfo hostInfo) {
        var node = new NodeState(
                nodeId,
                address,
                NodeState.NodeStatus.ONLINE,
                0.0,
                totalMemoryMb,
                0,
                0,
                0,
                0,
                Set.of(),
                labels,
                connectedSince,
                connectedSince,
                hostInfo);
        nodes.put(nodeId, node);
        return node;
    }

    public void remove(String nodeId) {
        nodes.remove(nodeId);
        cacheStatuses.remove(nodeId);
    }

    public Optional<NodeState> get(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public Collection<NodeState> getAll() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public NodeState updateStatus(
            String nodeId,
            double cpuUsage,
            long totalMemoryMb,
            long usedMemoryMb,
            long freeDiskMb,
            long totalDiskMb,
            int instanceCount,
            Set<Integer> usedPorts) {
        return nodes.computeIfPresent(
                nodeId,
                (ignored, existing) -> existing.withResourceUpdate(
                        cpuUsage, totalMemoryMb, usedMemoryMb, freeDiskMb, totalDiskMb, instanceCount, usedPorts));
    }

    public NodeState setStatus(String nodeId, NodeState.NodeStatus status) {
        return nodes.computeIfPresent(nodeId, (ignored, existing) -> existing.withStatus(status));
    }

    public NodeState recordHeartbeat(String nodeId, Instant timestamp) {
        return nodes.computeIfPresent(nodeId, (ignored, existing) -> existing.withHeartbeat(timestamp));
    }

    public void updateCacheStatus(String nodeId, NodeCacheStatus status) {
        cacheStatuses.put(nodeId, status);
    }

    public Optional<NodeCacheStatus> getCacheStatus(String nodeId) {
        return Optional.ofNullable(cacheStatuses.get(nodeId));
    }

    public int count() {
        return nodes.size();
    }
}
