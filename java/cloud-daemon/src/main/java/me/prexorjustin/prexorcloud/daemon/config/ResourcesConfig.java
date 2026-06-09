package me.prexorjustin.prexorcloud.daemon.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ResourcesConfig(@JsonProperty("maxMemoryMb") long maxMemoryMb) {

    public ResourcesConfig() {
        this(0);
    }

    /**
     * Returns the effective max memory. If configured as 0, auto-detects from the
     * system (80% of total physical memory).
     */
    public long effectiveMaxMemoryMb() {
        if (maxMemoryMb > 0) return maxMemoryMb;
        long totalMb = ((com.sun.management.OperatingSystemMXBean)
                                java.lang.management.ManagementFactory.getOperatingSystemMXBean())
                        .getTotalMemorySize()
                / (1024 * 1024);
        return (long) (totalMb * 0.8);
    }
}
