package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.List;

import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;

/**
 * Backend entrypoint contract for the platform module system.
 *
 * <p>
 * Each lifecycle hook receives a {@link ModuleContext} exposing the module's
 * manifest, capabilities, persistent storage, and the symmetric primitives
 * (events, logger, scheduler, HTTP, JSON) shared with the plugin SDK.
 * </p>
 */
public interface PlatformModule {

    default void onLoad(ModuleContext context) throws Exception {}

    /**
     * Register module-owned REST routes against the controller's HTTP server.
     * Routes are mounted under {@code /api/v1/modules/{moduleId}/}, share the
     * controller's auth + rate-limit middleware, and are dropped automatically
     * on uninstall or upgrade. Called once after {@link #onLoad} and before
     * {@link #onStart}; any module that needs to expose REST overrides this
     * instead of trying to keep a long-lived {@link RouteRegistrar} reference.
     */
    default void onRegisterRoutes(RouteRegistrar registrar) {}

    default void onStart(ModuleContext context) throws Exception {}

    default void onStop(ModuleContext context) throws Exception {}

    default void onUnload(ModuleContext context) throws Exception {}

    default void onUpgrade(ModuleContext context) throws Exception {}

    /**
     * Hot-reload hook for the fast {@code ACTIVE → RELOADING → ACTIVE} path. Called on
     * the <em>new</em> entrypoint when a reload-compatible jar replaces a running module
     * (controller entrypoint {@code reloadable: true}, identical capability declaration).
     *
     * <p>Unlike {@link #onUpgrade}, this is the <em>only</em> hook the reload path calls:
     * the outgoing module is never sent {@link #onStop} or {@link #onUnload}, so the new
     * instance must hand off its own live state — re-arm scheduler tasks, rebuild or
     * re-point caches — from inside {@code onReload}. The {@link ModuleContext} carries
     * {@code previousVersion} so the module can diff. Routes are re-registered separately
     * via {@link #onRegisterRoutes}. A module that does not implement this hook should not
     * set {@code reloadable: true}; the default no-op would silently keep stale state.
     */
    default void onReload(ModuleContext context) throws Exception {}

    /**
     * Capability handles exported by this module after activation.
     *
     * <p>Each handle binds a capability id to a typed value. The id must match
     * a {@code provides} entry in the module manifest; the type must be a
     * public interface or class consumers can legally resolve against.
     */
    default List<CapabilityHandle<?>> capabilityHandles() {
        return List.of();
    }

    /**
     * Optional liveness probe. The controller polls this on a fixed cadence for every
     * {@code ACTIVE} module and surfaces the latest result over REST
     * ({@code GET /api/v1/modules/platform/{id}/health}) and as the
     * {@code prexorcloud.module.health} metric.
     *
     * <p>Implementations must be cheap and non-blocking — check a cached liveness flag or a
     * last-success timestamp rather than performing a live round-trip on the polling thread. A
     * health check that throws is recorded as {@link ModuleHealth.Status#UNHEALTHY}. The default
     * returns {@link ModuleHealth#unknown()}, so a module that doesn't opt in reports
     * {@link ModuleHealth.Status#UNKNOWN} rather than a false-positive {@code HEALTHY}.
     */
    default ModuleHealth healthCheck() {
        return ModuleHealth.unknown();
    }
}
