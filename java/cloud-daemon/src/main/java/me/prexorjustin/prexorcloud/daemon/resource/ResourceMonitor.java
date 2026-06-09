package me.prexorjustin.prexorcloud.daemon.resource;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import me.prexorjustin.prexorcloud.protocol.NodeStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples system resources (CPU, memory, disk) and builds a NodeStatus proto
 * message.
 */
public final class ResourceMonitor {

    private static final Logger logger = LoggerFactory.getLogger(ResourceMonitor.class);

    private final Path instancesDir;

    public ResourceMonitor(Path instancesDir) {
        this.instancesDir = instancesDir;
    }

    public NodeStatus sample(int instanceCount, List<Integer> usedPorts) {
        var osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        double cpuUsage = osBean.getCpuLoad();
        if (cpuUsage < 0) cpuUsage = 0.0;

        long totalMemoryMb = osBean.getTotalMemorySize() / (1024 * 1024);
        long freeMemoryBytes = osBean.getFreeMemorySize();
        long usedMemoryMb = (osBean.getTotalMemorySize() - freeMemoryBytes) / (1024 * 1024);

        long freeDiskMb;
        long totalDiskMb;
        try {
            var store = Files.getFileStore(instancesDir);
            freeDiskMb = store.getUsableSpace() / (1024 * 1024);
            totalDiskMb = store.getTotalSpace() / (1024 * 1024);
        } catch (IOException e) {
            logger.warn("Failed to query disk space for {}: {}", instancesDir, e.getMessage());
            freeDiskMb = 0;
            totalDiskMb = 0;
        }

        return NodeStatus.newBuilder()
                .setCpuUsage(cpuUsage)
                .setTotalMemoryMb(totalMemoryMb)
                .setUsedMemoryMb(usedMemoryMb)
                .setFreeDiskMb(freeDiskMb)
                .setTotalDiskMb(totalDiskMb)
                .setInstanceCount(instanceCount)
                .addAllUsedPorts(usedPorts)
                .build();
    }
}
