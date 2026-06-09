package me.prexorjustin.prexorcloud.daemon.process;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Graceful process stop sequence: 1. Write "stop\n" to stdin (the only truly
 * graceful path on all platforms) 2. Wait shutdownTimeout 3. destroy() —
 * SIGTERM on Linux, TerminateProcess on Windows 4. Wait killTimeout 5.
 * destroyForcibly() + kill descendant processes
 */
public final class ProcessKiller {

    private static final Logger logger = LoggerFactory.getLogger(ProcessKiller.class);

    private ProcessKiller() {}

    /**
     * Stop a process gracefully with escalating force.
     *
     * @param process
     *            the running process
     * @param instanceId
     *            for logging
     * @param shutdownTimeoutSec
     *            seconds to wait after "stop" command
     * @param killTimeoutSec
     *            seconds to wait after SIGTERM before SIGKILL
     * @param force
     *            if true, skip graceful and go straight to force-kill
     */
    public static void stop(
            Process process, String instanceId, int shutdownTimeoutSec, int killTimeoutSec, boolean force) {
        if (!process.isAlive()) return;

        if (force) {
            logger.info("Force-killing instance {}", instanceId);
            destroyProcessTree(process, instanceId);
            return;
        }

        // Step 1: Write "stop" to stdin
        try {
            OutputStream stdin = process.getOutputStream();
            if (stdin != null) {
                stdin.write("stop\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                logger.debug("Sent 'stop' command to instance {}", instanceId);
            }
        } catch (Exception e) {
            logger.warn("Failed to send stop command to {}: {}", instanceId, e.getMessage());
        }

        // Step 2: Wait for graceful shutdown
        try {
            if (process.waitFor(shutdownTimeoutSec, TimeUnit.SECONDS)) {
                logger.debug("Instance {} stopped gracefully (exit={})", instanceId, process.exitValue());
                return;
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }

        // Step 3: SIGTERM (no-op on Windows where destroy = destroyForcibly)
        logger.info("Instance {} did not stop gracefully, sending SIGTERM", instanceId);
        process.destroy();

        try {
            if (process.waitFor(killTimeoutSec, TimeUnit.SECONDS)) {
                logger.debug("Instance {} terminated via SIGTERM (exit={})", instanceId, process.exitValue());
                return;
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }

        // Step 4: Force-kill the entire process tree
        logger.warn("Instance {} did not respond to SIGTERM, force-killing process tree", instanceId);
        destroyProcessTree(process, instanceId);
    }

    private static void destroyProcessTree(Process process, String instanceId) {
        // Kill descendant processes first (child JVMs, helper processes)
        process.toHandle().descendants().forEach(descendant -> {
            logger.debug(
                    "Killing descendant process {} (pid={}) of instance {}",
                    descendant.info().command().orElse("unknown"),
                    descendant.pid(),
                    instanceId);
            descendant.destroyForcibly();
        });
        process.destroyForcibly();
    }
}
