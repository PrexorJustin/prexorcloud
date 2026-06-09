package me.prexorjustin.prexorcloud.daemon.module;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import me.prexorjustin.prexorcloud.api.module.platform.DaemonModule;
import me.prexorjustin.prexorcloud.api.module.platform.ExitInfo;
import me.prexorjustin.prexorcloud.api.module.platform.InstanceHandle;
import me.prexorjustin.prexorcloud.api.module.platform.InstanceSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-node dispatcher for {@link DaemonModule} instance-lifecycle hooks.
 *
 * <p>Holds the live {@code DaemonModule} instances active on this daemon and fans out
 * pre/post-launch and stop hooks to each. Wired into {@code DaemonModuleManager}'s install
 * + uninstall paths so the active set tracks the lifecycle state machine. {@link
 * #dispatchInstanceStarting(InstanceSpec)} et al. are called by {@code ProcessManager}
 * around the spawn / exit path (PR 7d wires those calls).
 *
 * <p>Each dispatch wraps the module call in try/catch + SLF4J warn — a misbehaving module
 * must not abort instance lifecycle.
 */
public final class DaemonModuleHost {

    private static final Logger logger = LoggerFactory.getLogger(DaemonModuleHost.class);

    private final CopyOnWriteArrayList<Active> active = new CopyOnWriteArrayList<>();

    private record Active(String moduleId, DaemonModule module) {}

    /**
     * Register an active daemon module. Called by {@code DaemonModuleManager} after the
     * lifecycle manager activates the module. Idempotent: re-registering a moduleId
     * replaces the existing entry (used on upgrade).
     */
    public void register(String moduleId, DaemonModule module) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(module, "module");
        active.removeIf(a -> a.moduleId().equals(moduleId));
        active.add(new Active(moduleId, module));
    }

    /** Drop a module from the active set. Called on uninstall or when a module deactivates. */
    public void unregister(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        active.removeIf(a -> a.moduleId().equals(moduleId));
    }

    /**
     * Snapshot of the currently active modules. Mainly for testing — production callers
     * use the dispatch methods instead.
     */
    public List<DaemonModule> activeModules() {
        return active.stream().map(Active::module).toList();
    }

    public void dispatchInstanceStarting(InstanceSpec spec) {
        for (Active a : active) {
            try {
                a.module().onInstanceStarting(spec);
            } catch (Exception e) {
                logger.warn(
                        "module {} onInstanceStarting threw for {}: {}",
                        a.moduleId(),
                        spec.instanceId(),
                        e.getMessage());
            }
        }
    }

    public void dispatchInstanceStarted(InstanceHandle handle) {
        for (Active a : active) {
            try {
                a.module().onInstanceStarted(handle);
            } catch (Exception e) {
                logger.warn(
                        "module {} onInstanceStarted threw for {}: {}",
                        a.moduleId(),
                        handle.instanceId(),
                        e.getMessage());
            }
        }
    }

    public void dispatchInstanceStopping(InstanceHandle handle) {
        for (Active a : active) {
            try {
                a.module().onInstanceStopping(handle);
            } catch (Exception e) {
                logger.warn(
                        "module {} onInstanceStopping threw for {}: {}",
                        a.moduleId(),
                        handle.instanceId(),
                        e.getMessage());
            }
        }
    }

    public void dispatchInstanceStopped(InstanceHandle handle, ExitInfo exit) {
        for (Active a : active) {
            try {
                a.module().onInstanceStopped(handle, exit);
            } catch (Exception e) {
                logger.warn(
                        "module {} onInstanceStopped threw for {}: {}",
                        a.moduleId(),
                        handle.instanceId(),
                        e.getMessage());
            }
        }
    }
}
