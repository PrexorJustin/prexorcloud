package me.prexorjustin.prexorcloud.daemon.process;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;

import me.prexorjustin.prexorcloud.daemon.grpc.DaemonGrpcClient;
import me.prexorjustin.prexorcloud.daemon.template.PaperBootstrapCache;
import me.prexorjustin.prexorcloud.protocol.CrashReport;
import me.prexorjustin.prexorcloud.protocol.InstanceState;
import me.prexorjustin.prexorcloud.protocol.InstanceStatusUpdate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single Minecraft server process. Captures stdout/stderr, monitors
 * lifecycle, and reports status to the controller.
 *
 * <p>
 * Uses {@link StructuredTaskScope} (JEP 505) to manage the console capture and
 * exit monitor as a single unit of work.
 * </p>
 */
public final class ServerProcess {

    private static final Logger logger = LoggerFactory.getLogger(ServerProcess.class);
    private static final Set<String> BLOCKED_ENV_VARS = Set.of(
            "LD_PRELOAD",
            "LD_LIBRARY_PATH",
            "DYLD_INSERT_LIBRARIES",
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "CLASSPATH");

    private final String instanceId;
    private final String group;
    private final Path workingDir;
    private final ConsoleCapture consoleCapture;
    private final DaemonGrpcClient grpcClient;
    private final int port;
    private final int memoryMb;
    private final double cpuReservation;
    private final long diskReservationMb;
    private final List<String> jvmArgs;
    private final Map<String, String> env;
    private final String jarFile;
    private final String category;
    private final String nodeId;
    private final int shutdownTimeoutSec;
    private final int killTimeoutSec;
    private final java.util.function.Consumer<Boolean> onExit; // receives crashed flag

    private Process process;
    private long startTimeMs;
    private volatile InstanceState state = InstanceState.SCHEDULED;

    // Flexible Constructor Bodies (JEP 513): validate and compute before super/this
    public ServerProcess(
            String instanceId,
            String group,
            Path workingDir,
            int logBufferLines,
            int maxConsoleOutputLinesPerSecond,
            DaemonGrpcClient grpcClient,
            int port,
            int memoryMb,
            double cpuReservation,
            long diskReservationMb,
            List<String> jvmArgs,
            Map<String, String> env,
            String jarFile,
            String category,
            String pluginToken,
            String nodeId,
            int shutdownTimeoutSec,
            int killTimeoutSec,
            java.util.function.Consumer<Boolean> onExit) {
        // Build the full env map before assigning final fields (JEP 513)
        var fullEnv = new HashMap<>(env);
        if (pluginToken != null && !pluginToken.isBlank()) {
            fullEnv.put("CLOUD_PLUGIN_TOKEN", pluginToken);
        }
        fullEnv.put("CLOUD_CPU_RESERVATION", Double.toString(Math.max(0.0, cpuReservation)));
        fullEnv.put("CLOUD_DISK_RESERVATION_MB", Long.toString(Math.max(0, diskReservationMb)));

        this.instanceId = instanceId;
        this.group = group;
        this.workingDir = workingDir;
        this.grpcClient = grpcClient;
        this.port = port;
        this.memoryMb = memoryMb;
        this.cpuReservation = Math.max(0.0, cpuReservation);
        this.diskReservationMb = Math.max(0, diskReservationMb);
        this.jvmArgs = List.copyOf(jvmArgs);
        this.env = Map.copyOf(fullEnv);
        this.jarFile = jarFile;
        this.category = category != null ? category : "SERVER";
        this.nodeId = nodeId;
        this.shutdownTimeoutSec = shutdownTimeoutSec;
        this.killTimeoutSec = killTimeoutSec;
        this.onExit = onExit;

        this.consoleCapture =
                new ConsoleCapture(instanceId, logBufferLines, maxConsoleOutputLinesPerSecond, (id, line) -> {
                    if (grpcClient.isConnected()) {
                        grpcClient.sendConsoleOutput(id, line);
                    }
                });
    }

    /**
     * Start the server process. Uses Structured Concurrency (JEP 505) to run
     * console capture and exit monitoring as a unit of work.
     */
    public void start() throws Exception {
        reportState(InstanceState.PREPARING);

        List<String> command = buildCommand();
        logger.debug("Starting instance {} in {}: {}", instanceId, workingDir, String.join(" ", command));
        logger.debug(
                "Runtime isolation hints for {}: cpuReservation={}, diskReservationMb={}",
                instanceId,
                cpuReservation,
                diskReservationMb);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        Map<String, String> processEnv = pb.environment();
        env.forEach((key, value) -> {
            if (BLOCKED_ENV_VARS.contains(key.toUpperCase())) {
                logger.warn("Blocked dangerous environment variable '{}' for instance {}", key, instanceId);
            } else {
                processEnv.put(key, value);
            }
        });
        processEnv.put("CLOUD_INSTANCE_ID", instanceId);
        processEnv.put("CLOUD_GROUP", group);
        processEnv.put("CLOUD_PORT", String.valueOf(port));
        processEnv.put("CLOUD_NODE_ID", nodeId);
        String apiUrl = grpcClient.controllerApiUrl();
        if (!apiUrl.isBlank()) {
            processEnv.put("CLOUD_CONTROLLER_URL", apiUrl);
        }

        reportState(InstanceState.STARTING);
        process = pb.start();
        startTimeMs = System.currentTimeMillis();

        // Structured Concurrency (JEP 505): capture + monitor are related tasks
        // We use open() so both subtasks run concurrently and are managed together.
        // Note: we don't join here -- the scope lives for the process lifetime.
        // Instead we fire-and-forget via a virtual thread that owns the scope.
        Thread.startVirtualThread(() -> {
            try (var scope = StructuredTaskScope.open()) {
                scope.fork(() -> {
                    captureOutput();
                    return null;
                });
                scope.fork(() -> {
                    monitorExit();
                    return null;
                });
                scope.join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("Structured task scope failed for instance {}: {}", instanceId, e.getMessage(), e);
                reportState(InstanceState.CRASHED);
                onExit.accept(true);
            }
        });
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xmx" + memoryMb + "m");
        cmd.add("-Xms" + (memoryMb / 2) + "m");
        cmd.add("--add-opens");
        cmd.add("java.base/java.lang=ALL-UNNAMED");

        // Use CDS archive if available — skips class parsing on startup
        Path cdsPath = workingDir.resolve(PaperBootstrapCache.cdsArchiveName());
        if (java.nio.file.Files.exists(cdsPath)) {
            cmd.add("-XX:SharedArchiveFile=" + PaperBootstrapCache.cdsArchiveName());
        }

        cmd.addAll(jvmArgs);
        cmd.add("-jar");
        cmd.add(jarFile);
        if (!"PROXY".equalsIgnoreCase(category)) {
            cmd.add("--port");
            cmd.add(String.valueOf(port));
            cmd.add("--nogui");
        }
        return cmd;
    }

    private void captureOutput() {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consoleCapture.addLine(line);
            }
        } catch (Exception e) {
            if (process.isAlive()) {
                logger.warn("Console capture error for {}: {}", instanceId, e.getMessage());
            }
        }
    }

    private void monitorExit() {
        try {
            int exitCode = process.waitFor();
            long uptimeMs = System.currentTimeMillis() - startTimeMs;

            // Determine if this was a crash or a clean exit:
            // - If we initiated the stop (state == STOPPING), it's always a clean stop.
            // - If the process exited on its own with code 0 (e.g., /stop command),
            // treat it as a clean self-shutdown, not a crash.
            // - Any other unexpected exit (non-zero exit code) is a crash.
            boolean crashed = state != InstanceState.STOPPING && exitCode != 0;

            if (!crashed) {
                reportState(InstanceState.STOPPED);
                if (state != InstanceState.STOPPING) {
                    logger.debug(
                            "Instance {} self-shutdown cleanly (exit={}, uptime={}ms)", instanceId, exitCode, uptimeMs);
                } else {
                    logger.info("Instance {} stopped (exit={}, uptime={}ms)", instanceId, exitCode, uptimeMs);
                }
            } else {
                reportState(InstanceState.CRASHED);
                logger.warn("Instance {} crashed (exit={}, uptime={}ms)", instanceId, exitCode, uptimeMs);

                grpcClient.sendCrashReport(CrashReport.newBuilder()
                        .setInstanceId(instanceId)
                        .setGroup(group)
                        .setExitCode(exitCode)
                        .addAllLogTail(consoleCapture.getLines())
                        .setUptimeMs(uptimeMs)
                        .build());
            }

            onExit.accept(crashed);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop(boolean force) {
        if (process == null || !process.isAlive()) return;
        reportState(InstanceState.STOPPING);
        ProcessKiller.stop(process, instanceId, shutdownTimeoutSec, killTimeoutSec, force);
    }

    public void sendCommand(String command) {
        if (process == null || !process.isAlive()) {
            logger.warn("Cannot send command to {}: process not running", instanceId);
            return;
        }
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (Exception e) {
            logger.warn("Failed to send command to {}: {}", instanceId, e.getMessage());
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public InstanceState state() {
        return state;
    }

    public String instanceId() {
        return instanceId;
    }

    public String group() {
        return group;
    }

    public int port() {
        return port;
    }

    public long uptimeMs() {
        if (startTimeMs == 0) return 0;
        return System.currentTimeMillis() - startTimeMs;
    }

    public long startedAtMs() {
        return startTimeMs;
    }

    /** OS process id, or {@code -1} if the process has not started yet. */
    public long pid() {
        return process == null ? -1L : process.pid();
    }

    public ConsoleCapture consoleCapture() {
        return consoleCapture;
    }

    private void reportState(InstanceState newState) {
        this.state = newState;
        grpcClient.sendInstanceStatus(InstanceStatusUpdate.newBuilder()
                .setInstanceId(instanceId)
                .setState(newState)
                .setPort(port)
                .setPlayerCount(0)
                .setUptimeMs(uptimeMs())
                .build());
    }
}
