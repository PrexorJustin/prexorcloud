package me.prexorjustin.prexorcloud.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accumulates named shutdown steps and runs them in registration order.
 *
 * <p>
 * Register steps in the desired shutdown sequence during startup, then call
 * {@link #shutdown()} from the shutdown hook.
 * </p>
 */
final class ShutdownManager {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownManager.class);

    private final List<NamedStep> steps = new ArrayList<>();

    void register(String name, Step step) {
        steps.add(new NamedStep(name, step));
    }

    void shutdown() {
        for (NamedStep s : steps) {
            try {
                s.step.run();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                logger.warn("Shutdown step '{}' interrupted", s.name);
            } catch (Throwable t) {
                logger.warn("Shutdown step '{}' failed: {}", s.name, t.getMessage(), t);
            }
        }
    }

    static void awaitExecutor(ExecutorService exec, String name) throws InterruptedException {
        if (exec == null) return;
        exec.shutdownNow();
        if (!exec.awaitTermination(3, TimeUnit.SECONDS)) logger.warn("{} executor did not terminate in time", name);
    }

    private record NamedStep(String name, Step step) {}

    @FunctionalInterface
    interface Step {

        void run() throws Exception;
    }
}
