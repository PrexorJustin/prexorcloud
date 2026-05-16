package me.prexorjustin.prexorcloud.daemon.resource;

import java.lang.management.ManagementFactory;

import me.prexorjustin.prexorcloud.protocol.HostInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

/**
 * Collects static host information using OSHI (oshi-core-java25, FFM-based).
 * Called once at startup and included in the gRPC Handshake.
 */
public final class HostInfoCollector {

    private static final Logger logger = LoggerFactory.getLogger(HostInfoCollector.class);

    private HostInfoCollector() {}

    public static HostInfo collect() {
        var builder = HostInfo.newBuilder()
                .setArch(System.getProperty("os.arch", "unknown"))
                .setJavaVersion(Runtime.version().toString())
                .setJavaVendor(System.getProperty("java.vendor", "unknown"))
                .setJavaRuntime(System.getProperty("java.vm.name", "unknown"))
                .setJavaGc(detectGc());

        try {
            var si = new SystemInfo();
            var os = si.getOperatingSystem();
            var hal = si.getHardware();
            CentralProcessor cpu = hal.getProcessor();
            CentralProcessor.ProcessorIdentifier cpuId = cpu.getProcessorIdentifier();

            builder.setOsName(os.getFamily())
                    .setOsVersion(os.getVersionInfo().getVersion())
                    .setCpuModel(cpuId.getName())
                    .setCpuPhysicalCores(cpu.getPhysicalProcessorCount())
                    .setCpuLogicalCores(cpu.getLogicalProcessorCount())
                    .setCpuMaxFreqHz(cpu.getMaxFreq());
        } catch (Exception e) {
            logger.warn("Failed to collect host info via OSHI, falling back to JVM properties: {}", e.getMessage());
            builder.setOsName(System.getProperty("os.name", "unknown"))
                    .setOsVersion(System.getProperty("os.version", "unknown"))
                    .setCpuLogicalCores(Runtime.getRuntime().availableProcessors());
        }

        return builder.build();
    }

    private static String detectGc() {
        try {
            for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                String name = bean.getName();
                if (name.contains("G1")) return "G1";
                if (name.contains("ZGC")) return "ZGC";
                if (name.contains("Shenandoah")) return "Shenandoah";
                if (name.contains("ConcurrentMarkSweep")) return "CMS";
                if (name.startsWith("PS ") || name.contains("Parallel")) return "Parallel";
                if (name.contains("Serial")) return "Serial";
            }
            var beans = ManagementFactory.getGarbageCollectorMXBeans();
            return beans.isEmpty() ? "unknown" : beans.get(0).getName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
