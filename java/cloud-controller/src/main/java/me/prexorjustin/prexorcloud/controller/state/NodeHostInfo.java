package me.prexorjustin.prexorcloud.controller.state;

/**
 * Static host information reported by a daemon at handshake time.
 */
public record NodeHostInfo(
        String osName,
        String osVersion,
        String arch,
        String cpuModel,
        int cpuPhysicalCores,
        int cpuLogicalCores,
        long cpuMaxFreqHz,
        String javaVersion,
        String javaVendor,
        String javaRuntime,
        String javaGc) {

    public static final NodeHostInfo UNKNOWN = new NodeHostInfo(
            "unknown", "unknown", "unknown", "unknown", 0, 0, 0, "unknown", "unknown", "unknown", "unknown");
}
