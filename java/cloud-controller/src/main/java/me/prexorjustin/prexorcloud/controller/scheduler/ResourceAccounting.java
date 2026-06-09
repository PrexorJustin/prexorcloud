package me.prexorjustin.prexorcloud.controller.scheduler;

import me.prexorjustin.prexorcloud.controller.state.NodeState;

/**
 * Centralizes current per-instance resource accounting and oversubscription checks.
 */
public final class ResourceAccounting {

    public static final double MEMORY_HIGH_WATERMARK = 0.90;
    public static final double CPU_HIGH_WATERMARK = 0.85;
    public static final double CPU_HARD_LIMIT = 0.95;
    public static final long DISK_LOW_WATERMARK_MB = 1024;

    public record Projection(
            long totalMemoryMb,
            long usedMemoryMb,
            int requestedMemoryMb,
            long projectedUsedMemoryMb,
            long freeMemoryAfterMb,
            double currentCpuUsage,
            double requestedCpuReservation,
            double projectedCpuUsage,
            long freeDiskMb,
            long requestedDiskReservationMb,
            long freeDiskAfterMb,
            boolean fits,
            boolean memoryOvercommitted,
            boolean cpuOvercommitted,
            boolean diskOvercommitted,
            boolean memoryHighWatermark,
            boolean cpuHighWatermark,
            boolean diskLowWatermark) {}

    private ResourceAccounting() {}

    public static Projection project(NodeState node, InstanceRequest request) {
        long projectedUsed = node.usedMemoryMb() + request.memoryMb();
        long totalMemory = Math.max(node.totalMemoryMb(), 0);
        long freeAfter = totalMemory - projectedUsed;
        boolean memoryFits = totalMemory > 0 && freeAfter >= 0;
        boolean memoryOvercommitted = projectedUsed > totalMemory;
        boolean memoryHighWatermark = memoryFits
                && totalMemory > 0
                && ((double) projectedUsed / (double) totalMemory) >= MEMORY_HIGH_WATERMARK;

        double currentCpu = clamp(node.cpuUsage(), 0.0, 1.0);
        double requestedCpu = Math.max(0.0, request.cpuReservation());
        double projectedCpu = currentCpu + requestedCpu;
        boolean cpuFits = projectedCpu <= CPU_HARD_LIMIT;
        boolean cpuHighWatermark = cpuFits && projectedCpu >= CPU_HIGH_WATERMARK;

        long freeDisk = Math.max(node.freeDiskMb(), 0);
        long requestedDisk = Math.max(request.diskReservationMb(), 0);
        long freeDiskAfter = freeDisk - requestedDisk;
        boolean diskFits = requestedDisk == 0 || freeDiskAfter >= 0;
        boolean diskLowWatermark = diskFits && requestedDisk > 0 && freeDiskAfter <= DISK_LOW_WATERMARK_MB;

        return new Projection(
                totalMemory,
                node.usedMemoryMb(),
                request.memoryMb(),
                projectedUsed,
                freeAfter,
                currentCpu,
                requestedCpu,
                projectedCpu,
                freeDisk,
                requestedDisk,
                freeDiskAfter,
                memoryFits && cpuFits && diskFits,
                memoryOvercommitted,
                !cpuFits,
                !diskFits,
                memoryHighWatermark,
                cpuHighWatermark,
                diskLowWatermark);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
