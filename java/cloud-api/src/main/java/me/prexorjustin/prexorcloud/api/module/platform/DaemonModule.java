package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.List;

/**
 * Backend entrypoint contract for modules that run on the daemon (per-node).
 *
 * <p>Daemon modules run inside the daemon process on each node where the module is
 * installed. They participate in instance lifecycle hooks ({@code onInstanceStarting},
 * etc.) and observe the local node's capability registry. Daemon modules have no Mongo
 * storage; {@link ModuleContext#findMongoStorage()} returns {@link java.util.Optional#empty()}.
 *
 * <p>A module that needs both controller- and daemon-side behavior declares
 * {@code hosts: [controller, daemon]} in its manifest and ships a {@code PlatformModule}
 * implementation as the {@code backend.controller.entrypoint} and a {@code DaemonModule}
 * implementation as the {@code backend.daemon.entrypoint}. The two halves do not share
 * heap state; they communicate through the controller-bus events forwarded to the
 * daemon (subscribe-registration model — see Layer 7 doc).
 */
public interface DaemonModule {

    default void onLoad(ModuleContext context) throws Exception {}

    default void onStart(ModuleContext context) throws Exception {}

    default void onStop(ModuleContext context) throws Exception {}

    default void onUnload(ModuleContext context) throws Exception {}

    default void onUpgrade(ModuleContext context) throws Exception {}

    /**
     * Pre-launch hook for an instance about to start on this node. Modules may mutate
     * {@code spec.jvmArgs()} or {@code spec.env()} to inject flags or environment
     * variables; mutations are observed by the daemon when it builds the launch command.
     * Throwing aborts the start with an error report; misbehaving modules must not be
     * able to wedge the daemon, so the daemon wraps each dispatch with a SLF4J warn.
     */
    default void onInstanceStarting(InstanceSpec spec) throws Exception {}

    /** Fired after the instance process is spawned and the daemon has a PID. */
    default void onInstanceStarted(InstanceHandle handle) throws Exception {}

    /** Fired before the daemon stops the instance process (graceful or forced). */
    default void onInstanceStopping(InstanceHandle handle) throws Exception {}

    /** Fired after the instance process has exited (clean or crashed). */
    default void onInstanceStopped(InstanceHandle handle, ExitInfo exit) throws Exception {}

    /**
     * Capability handles exported by this module after activation. Same contract as
     * {@link PlatformModule#capabilityHandles()} but the binding is node-local —
     * cross-node visibility is out of scope for v1.
     */
    default List<CapabilityHandle<?>> capabilityHandles() {
        return List.of();
    }
}
