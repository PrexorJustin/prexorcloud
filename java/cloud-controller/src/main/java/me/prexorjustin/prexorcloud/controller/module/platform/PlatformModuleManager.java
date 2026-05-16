package me.prexorjustin.prexorcloud.controller.module.platform;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.modules.runtime.CapabilityRegistry;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleLifecycleManager;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleRouteRegistry;
import me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier;

/**
 * Authoritative controller-side runtime for stored platform modules.
 */
public final class PlatformModuleManager implements AutoCloseable {

    private static final String MUTATION_LEASE = "platform-modules:mutate";

    public record ManagedPlatformModule(
            String moduleId,
            String version,
            String sha256,
            Path jarPath,
            long sizeBytes,
            Instant storedAt,
            PlatformModuleManifest manifest,
            PlatformModuleStorageManager.StorageAllocation storage,
            ModuleLifecycleManager.ModuleState state,
            String lastError,
            List<CapabilityRegistry.UnresolvedRequirement> unresolvedRequirements) {}

    private record RuntimeBinding(
            PlatformModuleStore.StoredModule storedModule, PlatformModuleRuntimeFactory.LoadedRuntime runtime) {}

    private final PlatformModuleStore store;
    private final PlatformModuleRuntimeFactory runtimeFactory;
    private final CapabilityRegistry capabilityRegistry = new CapabilityRegistry();
    private final PlatformModuleStorageManager storageManager;
    private final ModuleLifecycleManager lifecycleManager;
    private final DistributedLeaseManager leaseManager;
    private final PlatformModuleSignatureVerifier signatureVerifier;
    private final ModuleRouteRegistry.Hook routeHook;
    private volatile ModuleDistributorHook distributorHook = ModuleDistributorHook.NOOP_HOOK;
    private final Map<String, RuntimeBinding> runtimesByModuleId = new LinkedHashMap<>();
    private volatile ModuleClassLoaderTracker classLoaderTracker;

    /**
     * Test-only checkpoint between jar accept/commit and classloader open. Fires once
     * (the field self-clears) so the standby-promotion harness can deterministically
     * fail the controller over after a module package has been content-addressed and
     * indexed in the on-disk store but before any classloader has been created.
     * Production code never sets this.
     */
    public volatile Runnable preLoadHookForTesting;

    public PlatformModuleManager(PlatformModuleStore store) {
        this(
                store,
                new PlatformModuleRuntimeFactory.JarRuntimeFactory(),
                new PlatformModuleStorageManager(
                        null,
                        null,
                        new me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices(),
                        new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public PlatformModuleManager(PlatformModuleStore store, PlatformModuleRuntimeFactory runtimeFactory) {
        this(
                store,
                runtimeFactory,
                new PlatformModuleStorageManager(
                        null,
                        null,
                        new me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices(),
                        new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public PlatformModuleManager(
            PlatformModuleStore store,
            PlatformModuleRuntimeFactory runtimeFactory,
            PlatformModuleStorageManager storageManager,
            DistributedLeaseManager leaseManager) {
        this(store, runtimeFactory, storageManager, leaseManager, PlatformModuleSignatureVerifier.NOOP);
    }

    public PlatformModuleManager(
            PlatformModuleStore store,
            PlatformModuleRuntimeFactory runtimeFactory,
            PlatformModuleStorageManager storageManager,
            PlatformModuleSignatureVerifier signatureVerifier) {
        this(store, runtimeFactory, storageManager, null, signatureVerifier);
    }

    public PlatformModuleManager(
            PlatformModuleStore store,
            PlatformModuleRuntimeFactory runtimeFactory,
            PlatformModuleStorageManager storageManager,
            DistributedLeaseManager leaseManager,
            PlatformModuleSignatureVerifier signatureVerifier) {
        this(store, runtimeFactory, storageManager, leaseManager, signatureVerifier, null);
    }

    public PlatformModuleManager(
            PlatformModuleStore store,
            PlatformModuleRuntimeFactory runtimeFactory,
            PlatformModuleStorageManager storageManager,
            DistributedLeaseManager leaseManager,
            PlatformModuleSignatureVerifier signatureVerifier,
            ModuleRouteRegistry routeRegistry) {
        this.store = Objects.requireNonNull(store, "store");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
        this.routeHook = routeRegistry == null ? ModuleRouteRegistry.NOOP_HOOK : routeRegistry.asHook();
        this.lifecycleManager = new ModuleLifecycleManager(capabilityRegistry, storageManager::resolve, this.routeHook);
        this.leaseManager = leaseManager;
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
    }

    public PlatformModuleManager(
            PlatformModuleStore store,
            PlatformModuleRuntimeFactory runtimeFactory,
            PlatformModuleStorageManager storageManager) {
        this(store, runtimeFactory, storageManager, (DistributedLeaseManager) null);
    }

    /**
     * Plug in the production {@link ModuleLifecycleManager.ContextFactory}
     * (typically {@link ControllerModuleContext}'s constructor) so modules
     * receive the live event bus and scheduler in subsequent lifecycle hooks.
     * Must be called before {@link #loadStoredModules}.
     */
    public void setContextFactory(ModuleLifecycleManager.ContextFactory contextFactory) {
        lifecycleManager.setContextFactory(contextFactory);
    }

    /**
     * Plug in the production {@link ModuleDistributorHook} so successful
     * install/upgrade/uninstall pushes daemon-host modules to connected daemons. The
     * controller bootstrap calls this once after wiring {@code ModuleDistributor};
     * tests leave the hook as {@link ModuleDistributorHook#NOOP_HOOK}.
     */
    public void setDistributorHook(ModuleDistributorHook hook) {
        this.distributorHook = hook == null ? ModuleDistributorHook.NOOP_HOOK : hook;
    }

    public synchronized void loadStoredModules() {
        closeAllRuntimes();
        capabilityRegistry.validateNoCycles(store.list().stream()
                .map(PlatformModuleStore.StoredModule::manifest)
                .toList());
        for (PlatformModuleStore.StoredModule storedModule : store.list()) {
            installStoredModule(storedModule);
        }
        reconcileUntilStable();
    }

    public synchronized boolean reconcileStoredModules() {
        var storedModules = store.list();
        capabilityRegistry.validateNoCycles(storedModules.stream()
                .map(PlatformModuleStore.StoredModule::manifest)
                .toList());

        Map<String, PlatformModuleStore.StoredModule> storedByModuleId = new LinkedHashMap<>();
        for (PlatformModuleStore.StoredModule storedModule : storedModules) {
            storedByModuleId.put(storedModule.moduleId(), storedModule);
        }

        boolean changed = false;
        for (String moduleId : List.copyOf(runtimesByModuleId.keySet())) {
            if (!storedByModuleId.containsKey(moduleId)) {
                unloadRuntime(moduleId);
                changed = true;
            }
        }

        for (PlatformModuleStore.StoredModule storedModule : storedModules) {
            RuntimeBinding current = runtimesByModuleId.get(storedModule.moduleId());
            if (current == null) {
                installStoredModule(storedModule);
                changed = true;
                continue;
            }
            if (!sameStoredModule(current.storedModule(), storedModule)) {
                unloadRuntime(storedModule.moduleId());
                installStoredModule(storedModule);
                changed = true;
            }
        }

        if (changed) {
            reconcileUntilStable();
        }
        return changed;
    }

    public synchronized ManagedPlatformModule install(Path sourceJar) {
        return withMutationLease(() -> {
            PlatformModuleStore.PreparedModule preparedModule = store.prepare(sourceJar);
            signatureVerifier.verify(new PlatformModuleSignatureVerifier.VerificationInput(
                    preparedModule.sourceJar(),
                    preparedModule.manifest().id(),
                    preparedModule.manifest().version(),
                    preparedModule.sha256()));
            capabilityRegistry.validateNoCycles(candidateManifests(preparedModule.manifest()));
            PlatformModuleStore.StoredModule storedModule = store.commit(preparedModule);

            Runnable hook = preLoadHookForTesting;
            if (hook != null) {
                preLoadHookForTesting = null;
                hook.run();
            }

            RuntimeBinding previous = runtimesByModuleId.get(storedModule.moduleId());
            ModuleLifecycleManager.ManagedModule previousManaged =
                    lifecycleManager.find(storedModule.moduleId()).orElse(null);
            boolean previousActive =
                    previousManaged != null && previousManaged.state() == ModuleLifecycleManager.ModuleState.ACTIVE;

            PlatformModuleRuntimeFactory.LoadedRuntime runtime;
            try {
                runtime = runtimeFactory.open(storedModule);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "failed to open platform module runtime for '" + storedModule.moduleId() + "'", e);
            }
            RuntimeBinding newBinding = new RuntimeBinding(storedModule, runtime);
            boolean success = false;
            try {
                ModuleLifecycleManager.ManagedModule managed;
                boolean reloadEligible = previousActive
                        && ModuleLifecycleManager.reloadCompatible(previousManaged.manifest(), storedModule.manifest());
                if (previousManaged == null || previousManaged.state() == ModuleLifecycleManager.ModuleState.UNLOADED) {
                    managed = lifecycleManager.install(
                            storedModule.jarPath(), storedModule.manifest(), runtime.entrypoint());
                } else if (reloadEligible) {
                    // Fast path: ACTIVE -> RELOADING -> ACTIVE, only onReload fires (ADR 28).
                    // The capability swap below still runs replaceModuleBindings, and
                    // closeBinding(previous) drops the outgoing classloader once onReload returns.
                    managed = lifecycleManager.reload(
                            storedModule.moduleId(),
                            storedModule.jarPath(),
                            storedModule.manifest(),
                            runtime.entrypoint());
                } else {
                    managed = lifecycleManager.upgrade(
                            storedModule.moduleId(),
                            storedModule.jarPath(),
                            storedModule.manifest(),
                            runtime.entrypoint());
                }

                runtimesByModuleId.put(storedModule.moduleId(), newBinding);
                if (managed.state() == ModuleLifecycleManager.ModuleState.ACTIVE) {
                    if (previousActive) {
                        capabilityRegistry.replaceModuleBindings(
                                storedModule.manifest(), runtime.entrypoint().capabilityHandles());
                    } else {
                        capabilityRegistry.activateModule(
                                storedModule.manifest(), runtime.entrypoint().capabilityHandles());
                    }
                } else if (previousActive) {
                    capabilityRegistry.deactivateModule(previousManaged.moduleId());
                }
                closeBinding(previous);
                reconcileUntilStable();
                success = true;
                String previousVersion = previousManaged == null
                        ? null
                        : previousManaged.manifest().version();
                distributorHook.onInstalled(storedModule, previousManaged != null, previousVersion);
                return snapshot(storedModule.moduleId()).orElseThrow();
            } finally {
                if (!success) {
                    closeLoadedRuntime(runtime);
                    if (previous != null) {
                        runtimesByModuleId.put(previous.storedModule().moduleId(), previous);
                        if (previousManaged != null
                                && previousManaged.state() == ModuleLifecycleManager.ModuleState.ACTIVE) {
                            capabilityRegistry.activateModule(
                                    previous.storedModule().manifest(),
                                    previous.runtime().entrypoint().capabilityHandles());
                        }
                    }
                }
            }
        });
    }

    public synchronized Optional<ManagedPlatformModule> uninstall(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        return withMutationLease(() -> {
            ModuleLifecycleManager.ManagedModule managed =
                    lifecycleManager.find(moduleId).orElse(null);
            RuntimeBinding binding = runtimesByModuleId.remove(moduleId);
            if (managed != null && managed.state() == ModuleLifecycleManager.ModuleState.ACTIVE) {
                capabilityRegistry.deactivateModule(moduleId);
            }
            if (managed != null) {
                lifecycleManager.uninstall(moduleId);
            }

            Optional<PlatformModuleStore.StoredModule> removed = store.remove(moduleId);
            closeBinding(binding);
            reconcileUntilStable();
            removed.ifPresent(stored -> distributorHook.onUninstalled(stored.moduleId()));

            return removed.map(stored -> new ManagedPlatformModule(
                    stored.moduleId(),
                    stored.version(),
                    stored.sha256(),
                    stored.jarPath(),
                    stored.sizeBytes(),
                    stored.storedAt(),
                    stored.manifest(),
                    storageManager.describe(stored.moduleId(), stored.manifest().storage()),
                    ModuleLifecycleManager.ModuleState.UNLOADED,
                    null,
                    List.of()));
        });
    }

    public synchronized List<ManagedPlatformModule> listModules() {
        return lifecycleManager.listModules().stream()
                .map(managed -> snapshot(managed.moduleId()))
                .flatMap(Optional::stream)
                .toList();
    }

    public synchronized Optional<ManagedPlatformModule> snapshot(String moduleId) {
        ModuleLifecycleManager.ManagedModule managed =
                lifecycleManager.find(moduleId).orElse(null);
        PlatformModuleStore.StoredModule storedModule = Optional.ofNullable(runtimesByModuleId.get(moduleId))
                .map(RuntimeBinding::storedModule)
                .orElse(null);

        if (managed == null || storedModule == null) {
            return store.find(moduleId)
                    .map(stored -> new ManagedPlatformModule(
                            stored.moduleId(),
                            stored.version(),
                            stored.sha256(),
                            stored.jarPath(),
                            stored.sizeBytes(),
                            stored.storedAt(),
                            stored.manifest(),
                            storageManager.describe(
                                    stored.moduleId(), stored.manifest().storage()),
                            ModuleLifecycleManager.ModuleState.UNLOADED,
                            null,
                            capabilityRegistry.unresolvedRequirements(stored.manifest())));
        }

        return Optional.of(new ManagedPlatformModule(
                storedModule.moduleId(),
                storedModule.version(),
                storedModule.sha256(),
                storedModule.jarPath(),
                storedModule.sizeBytes(),
                storedModule.storedAt(),
                managed.manifest(),
                storageManager.describe(
                        storedModule.moduleId(), managed.manifest().storage()),
                managed.state(),
                managed.lastError(),
                capabilityRegistry.unresolvedRequirements(managed.manifest())));
    }

    public synchronized ExtensionRegistry extensionRegistry() {
        return new ExtensionRegistry(lifecycleManager.listModules().stream()
                .filter(managed -> managed.state() != ModuleLifecycleManager.ModuleState.UNLOADED)
                .filter(managed -> managed.state() != ModuleLifecycleManager.ModuleState.FAILED)
                .map(ModuleLifecycleManager.ManagedModule::manifest)
                .toList());
    }

    public synchronized CapabilityRegistry capabilityRegistry() {
        return capabilityRegistry;
    }

    /**
     * Exposes the underlying content-addressed jar store. Read-only access for the
     * gRPC layer's {@link ModuleDistributor} (Layer 7 daemon-host module distribution)
     * — the manager retains exclusive write authority over the store.
     */
    public PlatformModuleStore platformStore() {
        return store;
    }

    public synchronized Optional<PlatformModuleStore.ArtifactContent> readArtifact(
            String moduleId, String relativePath) {
        return store.readArtifact(moduleId, relativePath);
    }

    public synchronized PlatformModuleStorageManager.StorageDropResult dropStorage(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        return withMutationLease(() -> {
            lifecycleManager.find(moduleId).ifPresent(managed -> {
                if (managed.state() != ModuleLifecycleManager.ModuleState.UNLOADED) {
                    throw new IllegalStateException("storage can only be dropped after uninstall; current state is "
                            + managed.state().name());
                }
            });
            return storageManager.drop(moduleId);
        });
    }

    @Override
    public synchronized void close() {
        closeAllRuntimes();
    }

    private void installStoredModule(PlatformModuleStore.StoredModule storedModule) {
        try {
            PlatformModuleRuntimeFactory.LoadedRuntime runtime = runtimeFactory.open(storedModule);
            runtimesByModuleId.put(storedModule.moduleId(), new RuntimeBinding(storedModule, runtime));
            ModuleLifecycleManager.ManagedModule managed =
                    lifecycleManager.install(storedModule.jarPath(), storedModule.manifest(), runtime.entrypoint());
            if (managed.state() == ModuleLifecycleManager.ModuleState.ACTIVE) {
                capabilityRegistry.activateModule(
                        storedModule.manifest(), runtime.entrypoint().capabilityHandles());
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to load stored module '" + storedModule.moduleId() + "'", e);
        }
    }

    private List<PlatformModuleManifest> candidateManifests(PlatformModuleManifest candidate) {
        return java.util.stream.Stream.concat(
                        store.list().stream()
                                .filter(stored -> !stored.moduleId().equals(candidate.id()))
                                .map(PlatformModuleStore.StoredModule::manifest),
                        java.util.stream.Stream.of(candidate))
                .toList();
    }

    private void reconcileUntilStable() {
        boolean changed;
        int guard = Math.max(1, lifecycleManager.listModules().size() + 1);
        do {
            changed = false;
            for (ModuleLifecycleManager.ManagedModule before : lifecycleManager.listModules()) {
                ModuleLifecycleManager.ManagedModule after = lifecycleManager.reconcile(before.moduleId());
                if (before.state() != after.state()) {
                    changed = true;
                    syncCapabilities(before, after);
                }
            }
        } while (changed && --guard > 0);
    }

    private void syncCapabilities(
            ModuleLifecycleManager.ManagedModule before, ModuleLifecycleManager.ManagedModule after) {
        if (before.state() == ModuleLifecycleManager.ModuleState.ACTIVE
                && after.state() != ModuleLifecycleManager.ModuleState.ACTIVE) {
            capabilityRegistry.deactivateModule(after.moduleId());
        }
        if (before.state() != ModuleLifecycleManager.ModuleState.ACTIVE
                && after.state() == ModuleLifecycleManager.ModuleState.ACTIVE) {
            RuntimeBinding binding = runtimesByModuleId.get(after.moduleId());
            if (binding != null) {
                capabilityRegistry.activateModule(
                        after.manifest(), binding.runtime().entrypoint().capabilityHandles());
            }
        }
    }

    private void closeAllRuntimes() {
        for (RuntimeBinding binding : runtimesByModuleId.values()) {
            closeBinding(binding);
        }
        runtimesByModuleId.clear();
    }

    private void unloadRuntime(String moduleId) {
        RuntimeBinding binding = runtimesByModuleId.remove(moduleId);
        ModuleLifecycleManager.ManagedModule managed =
                lifecycleManager.find(moduleId).orElse(null);
        if (managed != null && managed.state() == ModuleLifecycleManager.ModuleState.ACTIVE) {
            capabilityRegistry.deactivateModule(moduleId);
        }
        if (managed != null && managed.state() != ModuleLifecycleManager.ModuleState.UNLOADED) {
            lifecycleManager.uninstall(moduleId);
        }
        closeBinding(binding);
    }

    private static boolean sameStoredModule(
            PlatformModuleStore.StoredModule left, PlatformModuleStore.StoredModule right) {
        return left.moduleId().equals(right.moduleId())
                && left.version().equals(right.version())
                && left.sha256().equals(right.sha256());
    }

    private void closeBinding(RuntimeBinding binding) {
        if (binding == null) {
            return;
        }
        closeLoadedRuntime(binding.runtime());
    }

    private void closeLoadedRuntime(PlatformModuleRuntimeFactory.LoadedRuntime runtime) {
        ModuleClassLoaderTracker tracker = this.classLoaderTracker;
        if (tracker != null) {
            ClassLoader isolation = runtime.isolationClassLoader();
            if (isolation != null) {
                String moduleId = runtime.manifest().id();
                String version = runtime.manifest().version();
                tracker.track(moduleId, version, isolation);
            }
        }
        try (Closeable closeable = runtime.closeable()) {
            // try-with-resources guarantees deterministic close on unload
            if (closeable == null) {
                return;
            }
        } catch (IOException _) {
        }
    }

    /**
     * Inject a classloader leak tracker. The tracker observes URLClassLoader instances
     * after unload and reports leaks via Prometheus metrics and audit log entries.
     */
    public void setClassLoaderTracker(ModuleClassLoaderTracker tracker) {
        this.classLoaderTracker = tracker;
    }

    public ModuleClassLoaderTracker classLoaderTracker() {
        return this.classLoaderTracker;
    }

    private <T> T withMutationLease(java.util.function.Supplier<T> action) {
        if (leaseManager == null) {
            return action.get();
        }
        var lease = leaseManager.tryAcquireLease(MUTATION_LEASE);
        if (lease.isEmpty()) {
            throw new IllegalStateException(
                    "platform module mutation is already owned by another controller; retry once the lease is free");
        }
        try {
            return action.get();
        } finally {
            leaseManager.release(lease.get());
        }
    }
}
