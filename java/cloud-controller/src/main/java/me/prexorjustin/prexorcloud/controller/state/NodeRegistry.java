package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class NodeRegistry {

    private final Map<String, NodeState> nodes = new ConcurrentHashMap<>();
    private final Map<String, NodeCacheStatus> cacheStatuses = new ConcurrentHashMap<>();

    /** Outcome of an atomic placement reservation: the updated node plus the port that was claimed. */
    public record Reservation(NodeState node, int port) {}

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

    /**
     * Atomically reserve a port + memory on a node, deriving both from the node's
     * <em>current</em> value (not a caller-held snapshot). This is the single-writer
     * guard against concurrent placements: when the per-tier scheduler fan-out forks
     * many groups in parallel, two tasks selecting the same node would otherwise read
     * the same snapshot, pick the same lowest-free port, and write back
     * {@code snapshot ∪ ownPort} — last writer wins, the other reservation lost.
     * Serialising select-derived mutation through {@link ConcurrentHashMap#computeIfPresent}
     * makes each reservation derive from the previous one's result instead.
     *
     * @return the claimed reservation, or empty if the node is gone or has no free
     *     port in {@code [portRangeStart, portRangeEnd]}.
     */
    public Optional<Reservation> reservePlacement(String nodeId, long memoryMb, int portRangeStart, int portRangeEnd) {
        var holder = new AtomicReference<Reservation>();
        nodes.computeIfPresent(nodeId, (ignored, existing) -> {
            // Lowest free port in range (same semantics as PortAllocator; inlined to keep
            // the state package free of a scheduler-package dependency).
            int port = -1;
            for (int candidate = portRangeStart; candidate <= portRangeEnd; candidate++) {
                if (!existing.usedPorts().contains(candidate)) {
                    port = candidate;
                    break;
                }
            }
            if (port < 0) {
                return existing; // no free port; leave node unchanged, holder stays null
            }
            Set<Integer> updatedPorts = new HashSet<>(existing.usedPorts());
            updatedPorts.add(port);
            NodeState next = existing.withResourceUpdate(
                    existing.cpuUsage(),
                    existing.totalMemoryMb(),
                    existing.usedMemoryMb() + memoryMb,
                    existing.freeDiskMb(),
                    existing.totalDiskMb(),
                    existing.instanceCount() + 1,
                    updatedPorts);
            holder.set(new Reservation(next, port));
            return next;
        });
        return Optional.ofNullable(holder.get());
    }

    /**
     * Atomically release a port + memory previously claimed by {@link #reservePlacement},
     * deriving the new value from the node's current state (placement rollback path —
     * the race-free counterpart to reservation).
     */
    public NodeState releasePlacement(String nodeId, long memoryMb, int port) {
        return nodes.computeIfPresent(nodeId, (ignored, existing) -> {
            Set<Integer> updatedPorts = new HashSet<>(existing.usedPorts());
            updatedPorts.remove(port);
            return existing.withResourceUpdate(
                    existing.cpuUsage(),
                    existing.totalMemoryMb(),
                    Math.max(0, existing.usedMemoryMb() - memoryMb),
                    existing.freeDiskMb(),
                    existing.totalDiskMb(),
                    Math.max(0, existing.instanceCount() - 1),
                    updatedPorts);
        });
    }

    /**
     * Atomically release a single port (instance teardown path; the controller knows the
     * port from the instance record but not its memory, and the instance count + memory
     * reconcile on the next daemon telemetry tick). Keeping the node's {@code usedPorts}
     * authoritative — never accumulating stale ports from removed instances — is what lets
     * {@link #applyTelemetry} safely union daemon-reported ports with the existing set
     * without leaking. Port-only so it composes with {@link #releasePlacement} (rollback)
     * without double-decrementing the instance count. Returns the updated node, or the
     * unchanged node if the port was not held, or null if the node is gone.
     */
    public NodeState releasePort(String nodeId, int port) {
        return nodes.computeIfPresent(nodeId, (ignored, existing) -> {
            if (!existing.usedPorts().contains(port)) {
                return existing;
            }
            Set<Integer> updatedPorts = new HashSet<>(existing.usedPorts());
            updatedPorts.remove(port);
            return existing.withResourceUpdate(
                    existing.cpuUsage(),
                    existing.totalMemoryMb(),
                    existing.usedMemoryMb(),
                    existing.freeDiskMb(),
                    existing.totalDiskMb(),
                    existing.instanceCount(),
                    updatedPorts);
        });
    }

    /**
     * Apply a daemon NodeStatus heartbeat as telemetry that can never recycle a
     * controller-held reservation (the daemon does not know about ports/memory the
     * controller reserved for an instance it has not started yet). CPU / total-memory /
     * disk are taken verbatim (machine truth); used ports are UNIONed with the node's
     * existing set so a reserved-but-not-yet-started port survives; memory is floored at
     * the controller's running reservation total while any instance on the node is still
     * pre-running, then follows the daemon once everything has started or stopped
     * (self-healing — no leak). Instance count is floored at the controller's own count.
     */
    public NodeState applyTelemetry(
            String nodeId,
            double cpuUsage,
            long totalMemoryMb,
            long reportedUsedMemoryMb,
            long freeDiskMb,
            long totalDiskMb,
            int reportedInstanceCount,
            Set<Integer> reportedPorts,
            int controllerInstanceCount,
            boolean hasPreRunningInstance) {
        return nodes.computeIfPresent(nodeId, (ignored, existing) -> {
            Set<Integer> mergedPorts = new HashSet<>(existing.usedPorts());
            mergedPorts.addAll(reportedPorts);
            long usedMemoryMb = hasPreRunningInstance
                    ? Math.max(reportedUsedMemoryMb, existing.usedMemoryMb())
                    : reportedUsedMemoryMb;
            int instanceCount = Math.max(reportedInstanceCount, controllerInstanceCount);
            return existing.withResourceUpdate(
                    cpuUsage, totalMemoryMb, usedMemoryMb, freeDiskMb, totalDiskMb, instanceCount, mergedPorts);
        });
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
