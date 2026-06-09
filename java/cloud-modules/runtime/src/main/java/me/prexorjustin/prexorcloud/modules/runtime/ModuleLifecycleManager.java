package me.prexorjustin.prexorcloud.modules.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.platform.CapabilityDeclaration;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleHealth;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleStorage;
import me.prexorjustin.prexorcloud.common.logging.CorrelationContext;

/**
 * Controller-side lifecycle state machine for platform modules.
 */
public final class ModuleLifecycleManager {

    @FunctionalInterface
    public interface RequirementResolver {
        boolean requirementsSatisfied(PlatformModuleManifest manifest);
    }

    @FunctionalInterface
    public interface StorageResolver {
        PlatformModuleStorage resolve(PlatformModuleManifest manifest);
    }

    public enum ModuleState {
        INSTALLED,
        WAITING,
        ACTIVE,
        RELOADING,
        STOPPING,
        UNLOADED,
        FAILED
    }

    public record ManagedModule(
            String moduleId,
            Path jarPath,
            PlatformModuleManifest manifest,
            PlatformModuleStorage storage,
            PlatformModule entrypoint,
            ModuleState state,
            String lastError,
            Instant updatedAt) {}

    /**
     * Builds the {@link ModuleContext} handed to a module's lifecycle hooks.
     * The default implementation produces a {@link NoopModuleContext}; the
     * production caller (controller bootstrap) overrides with one that wires
     * the live {@code EventBus} and {@code TaskScheduler}.
     */
    @FunctionalInterface
    public interface ContextFactory {
        ModuleContext create(
                PlatformModuleManifest manifest,
                Path jarPath,
                String previousVersion,
                Map<String, Object> capabilities,
                PlatformModuleStorage storage);
    }

    /** No-op context factory used when no production wiring is provided. */
    public static ContextFactory noopContextFactory() {
        return NoopModuleContext::new;
    }

    private final RequirementResolver requirementResolver;
    private final StorageResolver storageResolver;
    private final CapabilityRegistry capabilityRegistry;
    private final ModuleRouteRegistry.Hook routeHook;
    private volatile ContextFactory contextFactory;
    private final Map<String, ManagedModule> modules = new LinkedHashMap<>();

    /**
     * Replace the current context factory. Called by the controller bootstrap
     * after the live {@code EventBus} and {@code TaskScheduler} are
     * available, so modules see those services in subsequent lifecycle hooks.
     * Construction defaults to {@link #noopContextFactory()} for tests; this
     * setter swaps in the production factory once.
     */
    public synchronized void setContextFactory(ContextFactory contextFactory) {
        this.contextFactory = contextFactory == null ? noopContextFactory() : contextFactory;
    }

    public ModuleLifecycleManager(CapabilityRegistry capabilityRegistry) {
        this(
                capabilityRegistry::requirementsSatisfied,
                manifest -> PlatformModuleStorage.none(manifest.id(), manifest.storage()),
                capabilityRegistry,
                ModuleRouteRegistry.NOOP_HOOK,
                noopContextFactory());
    }

    public ModuleLifecycleManager(RequirementResolver requirementResolver) {
        this(
                requirementResolver,
                manifest -> PlatformModuleStorage.none(manifest.id(), manifest.storage()),
                null,
                ModuleRouteRegistry.NOOP_HOOK,
                noopContextFactory());
    }

    public ModuleLifecycleManager(RequirementResolver requirementResolver, StorageResolver storageResolver) {
        this(requirementResolver, storageResolver, null, ModuleRouteRegistry.NOOP_HOOK, noopContextFactory());
    }

    public ModuleLifecycleManager(CapabilityRegistry capabilityRegistry, StorageResolver storageResolver) {
        this(
                capabilityRegistry::requirementsSatisfied,
                storageResolver,
                capabilityRegistry,
                ModuleRouteRegistry.NOOP_HOOK,
                noopContextFactory());
    }

    public ModuleLifecycleManager(
            CapabilityRegistry capabilityRegistry,
            StorageResolver storageResolver,
            ModuleRouteRegistry.Hook routeHook) {
        this(
                capabilityRegistry::requirementsSatisfied,
                storageResolver,
                capabilityRegistry,
                routeHook,
                noopContextFactory());
    }

    public ModuleLifecycleManager(
            CapabilityRegistry capabilityRegistry,
            StorageResolver storageResolver,
            ModuleRouteRegistry.Hook routeHook,
            ContextFactory contextFactory) {
        this(capabilityRegistry::requirementsSatisfied, storageResolver, capabilityRegistry, routeHook, contextFactory);
    }

    private ModuleLifecycleManager(
            RequirementResolver requirementResolver,
            StorageResolver storageResolver,
            CapabilityRegistry capabilityRegistry,
            ModuleRouteRegistry.Hook routeHook,
            ContextFactory contextFactory) {
        this.requirementResolver = Objects.requireNonNull(requirementResolver);
        this.storageResolver = Objects.requireNonNull(storageResolver);
        this.capabilityRegistry = capabilityRegistry;
        this.routeHook = routeHook == null ? ModuleRouteRegistry.NOOP_HOOK : routeHook;
        this.contextFactory = contextFactory == null ? noopContextFactory() : contextFactory;
    }

    public synchronized ManagedModule install(
            Path jarPath, PlatformModuleManifest manifest, PlatformModule entrypoint) {
        try (var ignored = moduleScope(manifest)) {
            requireInstallable(jarPath, manifest, entrypoint);
            PlatformModuleStorage storage = storageResolver.resolve(manifest);

            String moduleId = manifest.id();
            ManagedModule installed = new ManagedModule(
                    moduleId, jarPath, manifest, storage, entrypoint, ModuleState.INSTALLED, null, Instant.now());
            modules.put(moduleId, installed);

            try {
                entrypoint.onLoad(contextFor(installed, null));
                routeHook.clearRoutes(moduleId);
                entrypoint.onRegisterRoutes(routeHook.registrarFor(moduleId));
            } catch (Exception e) {
                routeHook.clearRoutes(moduleId);
                return fail(moduleId, e);
            }

            return reconcile(moduleId);
        }
    }

    public synchronized ManagedModule upgrade(
            String moduleId, Path jarPath, PlatformModuleManifest manifest, PlatformModule entrypoint) {
        try (var ignored = moduleScope(manifest)) {
            ManagedModule existing = requireExisting(moduleId);
            if (!manifest.id().equals(moduleId)) {
                throw new IllegalArgumentException("replacement manifest id '" + manifest.id()
                        + "' does not match target module '" + moduleId + "'");
            }
            PlatformModuleStorage storage = storageResolver.resolve(manifest);

            if (existing.state() == ModuleState.ACTIVE) {
                update(
                        moduleId,
                        existing.state() == ModuleState.ACTIVE ? ModuleState.STOPPING : existing.state(),
                        null);
                try {
                    existing.entrypoint()
                            .onStop(contextFor(existing, existing.manifest().version()));
                } catch (Exception e) {
                    return fail(moduleId, e);
                }
            }

            try {
                existing.entrypoint()
                        .onUnload(contextFor(existing, existing.manifest().version()));
            } catch (Exception e) {
                return fail(moduleId, e);
            }

            // Drop the previous module's routes before the new entrypoint registers its own.
            // Failure to clear before re-register would leave the upgrade with stale routes
            // pointing at handlers in the old (about-to-be-GC'd) classloader.
            routeHook.clearRoutes(moduleId);

            ManagedModule replacement = new ManagedModule(
                    moduleId, jarPath, manifest, storage, entrypoint, ModuleState.INSTALLED, null, Instant.now());
            modules.put(moduleId, replacement);

            ModuleContext context = contextFor(replacement, existing.manifest().version());
            try {
                entrypoint.onLoad(context);
                entrypoint.onUpgrade(context);
                entrypoint.onRegisterRoutes(routeHook.registrarFor(moduleId));
            } catch (Exception e) {
                routeHook.clearRoutes(moduleId);
                return fail(moduleId, e);
            }

            return reconcile(moduleId);
        }
    }

    /**
     * Fast-path replacement for an {@code ACTIVE}, reload-compatible module: drives
     * {@code ACTIVE → RELOADING → ACTIVE} invoking only {@link PlatformModule#onReload}
     * on the new entrypoint — the outgoing module is never stopped or unloaded, so it
     * must hand off its own live state from inside {@code onReload}. Routes are still
     * cleared and re-registered: route handlers are classes in the outgoing classloader
     * and cannot be carried across. If {@code onReload} throws, the module is left
     * {@code FAILED} with no rollback (see ADR 28). Callers must gate on
     * {@link #reloadCompatible} first; this method re-checks and rejects an incompatible
     * or non-active module.
     */
    public synchronized ManagedModule reload(
            String moduleId, Path jarPath, PlatformModuleManifest manifest, PlatformModule entrypoint) {
        try (var ignored = moduleScope(manifest)) {
            ManagedModule existing = requireExisting(moduleId);
            if (!manifest.id().equals(moduleId)) {
                throw new IllegalArgumentException("replacement manifest id '" + manifest.id()
                        + "' does not match target module '" + moduleId + "'");
            }
            if (existing.state() != ModuleState.ACTIVE) {
                throw new IllegalStateException(
                        "reload requires the module to be ACTIVE; '" + moduleId + "' is " + existing.state());
            }
            if (!reloadCompatible(existing.manifest(), manifest)) {
                throw new IllegalArgumentException(
                        "module '" + moduleId + "' is not reload-compatible with the running version");
            }
            PlatformModuleStorage storage = storageResolver.resolve(manifest);

            update(moduleId, ModuleState.RELOADING, null);

            // Routes point at handler classes in the outgoing classloader — drop them
            // before the new entrypoint re-registers its own, exactly as upgrade() does.
            routeHook.clearRoutes(moduleId);

            ManagedModule reloaded = new ManagedModule(
                    moduleId, jarPath, manifest, storage, entrypoint, ModuleState.RELOADING, null, Instant.now());
            modules.put(moduleId, reloaded);

            ModuleContext context = contextFor(reloaded, existing.manifest().version());
            try {
                entrypoint.onReload(context);
                entrypoint.onRegisterRoutes(routeHook.registrarFor(moduleId));
            } catch (Exception e) {
                routeHook.clearRoutes(moduleId);
                return fail(moduleId, e);
            }

            return update(moduleId, ModuleState.ACTIVE, null);
        }
    }

    /**
     * A replacement jar is reload-compatible with the running version when the new
     * manifest opts in — its controller entrypoint declares {@code reloadable: true} —
     * and its capability declaration ({@code provides} + {@code requires}) is identical
     * to the running version's. Any capability-shape change forces the full
     * {@link #upgrade} path, which re-reconciles requirements and re-binds consumers.
     */
    public static boolean reloadCompatible(PlatformModuleManifest running, PlatformModuleManifest replacement) {
        PlatformModuleManifest.EntrypointSpec controller =
                replacement.backend() == null ? null : replacement.backend().controller();
        if (controller == null || !controller.reloadable()) {
            return false;
        }
        CapabilityDeclaration before = running.capabilities();
        CapabilityDeclaration after = replacement.capabilities();
        return before.provides().equals(after.provides()) && before.requires().equals(after.requires());
    }

    public synchronized ManagedModule reconcile(String moduleId) {
        ManagedModule current = requireExisting(moduleId);
        try (var ignored = moduleScope(current.manifest())) {
            if (current.state() == ModuleState.UNLOADED || current.state() == ModuleState.FAILED) {
                return current;
            }

            boolean requirementsSatisfied = requirementResolver.requirementsSatisfied(current.manifest());
            if ((current.state() == ModuleState.INSTALLED || current.state() == ModuleState.WAITING)
                    && requirementsSatisfied) {
                try {
                    current.entrypoint().onStart(contextFor(current, null));
                    return update(moduleId, ModuleState.ACTIVE, null);
                } catch (Exception e) {
                    return fail(moduleId, e);
                }
            }

            if ((current.state() == ModuleState.INSTALLED || current.state() == ModuleState.WAITING)
                    && !requirementsSatisfied) {
                return update(moduleId, ModuleState.WAITING, null);
            }

            if (current.state() == ModuleState.ACTIVE && !requirementsSatisfied) {
                update(moduleId, ModuleState.STOPPING, null);
                try {
                    current.entrypoint().onStop(contextFor(current, null));
                    return update(moduleId, ModuleState.WAITING, null);
                } catch (Exception e) {
                    return fail(moduleId, e);
                }
            }

            return current;
        }
    }

    public synchronized List<ManagedModule> reconcileAll() {
        List<String> moduleIds = new ArrayList<>(modules.keySet());
        List<ManagedModule> reconciled = new ArrayList<>(moduleIds.size());
        for (String moduleId : moduleIds) {
            reconciled.add(reconcile(moduleId));
        }
        return List.copyOf(reconciled);
    }

    public synchronized ManagedModule uninstall(String moduleId) {
        ManagedModule current = requireExisting(moduleId);
        try (var ignored = moduleScope(current.manifest())) {
            if (current.state() == ModuleState.UNLOADED) {
                return current;
            }
            if (current.state() == ModuleState.ACTIVE) {
                update(moduleId, ModuleState.STOPPING, null);
                try {
                    current.entrypoint().onStop(contextFor(current, null));
                } catch (Exception e) {
                    return fail(moduleId, e);
                }
            }

            ManagedModule latest = requireExisting(moduleId);
            try {
                latest.entrypoint().onUnload(contextFor(latest, null));
                routeHook.clearRoutes(moduleId);
                return update(moduleId, ModuleState.UNLOADED, null);
            } catch (Exception e) {
                routeHook.clearRoutes(moduleId);
                return fail(moduleId, e);
            }
        }
    }

    public synchronized Optional<ManagedModule> find(String moduleId) {
        return Optional.ofNullable(modules.get(moduleId));
    }

    public synchronized List<ManagedModule> listModules() {
        return List.copyOf(modules.values());
    }

    /**
     * Poll {@link PlatformModule#healthCheck()} on every currently-{@code ACTIVE} module and return
     * the results keyed by module id. The active set is snapshotted under the lock via
     * {@link #listModules()}, then the module code is invoked <em>outside</em> the lock — a slow or
     * misbehaving health check must not stall install / reconcile / uninstall. A check that throws
     * is reported as {@link ModuleHealth#unhealthy}. Modules that don't override {@code healthCheck}
     * report {@link ModuleHealth.Status#UNKNOWN}.
     */
    public Map<String, ModuleHealth> pollHealth() {
        Map<String, ModuleHealth> result = new LinkedHashMap<>();
        for (ManagedModule module : listModules()) {
            if (module.state() != ModuleState.ACTIVE) {
                continue;
            }
            result.put(module.moduleId(), pollHealth(module));
        }
        return result;
    }

    private ModuleHealth pollHealth(ManagedModule module) {
        try (var ignored = moduleScope(module.manifest())) {
            ModuleHealth health = module.entrypoint().healthCheck();
            return health != null ? health : ModuleHealth.unknown();
        } catch (Exception e) {
            return ModuleHealth.unhealthy("healthCheck threw: " + e.getClass().getSimpleName());
        }
    }

    private void requireInstallable(Path jarPath, PlatformModuleManifest manifest, PlatformModule entrypoint) {
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(entrypoint, "entrypoint");

        ManagedModule existing = modules.get(manifest.id());
        if (existing != null && existing.state() != ModuleState.UNLOADED) {
            throw new IllegalStateException("module already installed: " + manifest.id());
        }
    }

    private ManagedModule requireExisting(String moduleId) {
        ManagedModule existing = modules.get(moduleId);
        if (existing == null) {
            throw new IllegalArgumentException("unknown module: " + moduleId);
        }
        return existing;
    }

    private ManagedModule update(String moduleId, ModuleState state, String lastError) {
        ManagedModule current = requireExisting(moduleId);
        ManagedModule updated = new ManagedModule(
                current.moduleId(),
                current.jarPath(),
                current.manifest(),
                current.storage(),
                current.entrypoint(),
                state,
                lastError,
                Instant.now());
        modules.put(moduleId, updated);
        return updated;
    }

    private ManagedModule fail(String moduleId, Exception e) {
        return update(moduleId, ModuleState.FAILED, e.getMessage());
    }

    private ModuleContext contextFor(ManagedModule module, String previousVersion) {
        Map<String, Object> capabilities =
                capabilityRegistry == null ? Map.of() : capabilityRegistry.resolveRequiredHandles(module.manifest());
        return contextFactory.create(
                module.manifest(), module.jarPath(), previousVersion, capabilities, module.storage());
    }

    private static CorrelationContext.Scope moduleScope(PlatformModuleManifest manifest) {
        return CorrelationContext.open(Map.of(
                "moduleId", manifest.id(),
                "moduleVersion", manifest.version(),
                "capabilityIds", capabilityIds(manifest)));
    }

    private static String capabilityIds(PlatformModuleManifest manifest) {
        return manifest.capabilities().provides().stream()
                .map(provide -> provide.id())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}
