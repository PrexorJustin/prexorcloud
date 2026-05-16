package me.prexorjustin.prexorcloud.api.module.platform;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.scheduling.TaskScheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

/**
 * Context handed to {@link PlatformModule} lifecycle hooks.
 *
 * <p>
 * Exposes (a) the module's own metadata (manifest, jar location, upgrade hint),
 * (b) the cloud-side capabilities the module declared as required, (c) shared
 * cross-cutting primitives (event bus, logger, scheduler, HTTP, JSON), and (d)
 * the persistent storage attached to this module.
 * </p>
 *
 * <p>
 * The interface is implemented by {@code ControllerModuleContext} (in the
 * controller process) and {@code DaemonModuleContext} (in each daemon process).
 * Modules that target both hosts can branch on {@link #host()}.
 * </p>
 */
public interface ModuleContext {

    // ── Module identity ──────────────────────────────────────────────────────

    PlatformModuleManifest manifest();

    Path jarPath();

    /** {@code ""} when this is a fresh install; previous version string when upgrading. */
    String previousVersion();

    default boolean isUpgrade() {
        String previous = previousVersion();
        return previous != null && !previous.isBlank();
    }

    /** Process this module is hosted by; lets dual-host modules branch behavior. */
    ModuleHost host();

    // ── Capabilities ─────────────────────────────────────────────────────────

    /**
     * Look up a capability declared as {@code requires} in the manifest.
     * Returns empty if the capability is currently unbound (provider absent
     * or not yet activated).
     */
    <T> Optional<T> findCapability(String capabilityId, Class<T> type);

    /**
     * Like {@link #findCapability} but throws if the capability is unbound.
     * Use only for capabilities the module cannot meaningfully run without.
     */
    <T> T requireCapability(String capabilityId, Class<T> type);

    // ── Persistent storage ──────────────────────────────────────────────────

    Optional<ModuleDataStore> findMongoStorage();

    ModuleDataStore requireMongoStorage();

    Optional<PlatformRedisStorage> findRedisStorage();

    PlatformRedisStorage requireRedisStorage();

    // ── Symmetric cross-cutting primitives (mirror CloudPluginContext) ──────

    /**
     * Cluster-wide event bus. Modules subscribe to lifecycle events
     * (player join/leave, instance state changes, deployments) here. The
     * implementation is shared with plugin-side {@code CloudPluginContext.events()}
     * — one contract, two host processes.
     */
    EventBus events();

    /**
     * SLF4J logger pre-namespaced as {@code "module:<id>"} so module log
     * lines are attributable in mixed-source log streams.
     */
    Logger logger();

    /**
     * Task scheduler for module-owned async work (background reconciliation,
     * periodic flushes, delayed cleanup). Lifecycle is owned by the host —
     * tasks are cancelled automatically on module stop.
     */
    TaskScheduler scheduler();

    /**
     * Pre-configured outbound {@link HttpClient}. Use for webhooks, third-party
     * APIs, anything outside the cluster. The pool is shared across modules
     * so per-module connection cost is amortized.
     */
    HttpClient httpClient();

    /**
     * Standard Jackson {@link ObjectMapper} (java-time module, ISO-8601
     * timestamps, NON_NULL serialization, lenient on unknown properties).
     * Use for REST payloads, event serialization, anything wire-format.
     */
    ObjectMapper json();
}
