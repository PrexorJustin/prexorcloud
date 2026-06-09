package me.prexorjustin.prexorcloud.daemon.module;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import me.prexorjustin.prexorcloud.api.module.platform.DaemonModule;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleHost;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleStorage;
import me.prexorjustin.prexorcloud.modules.runtime.CapabilityRegistry;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleLifecycleManager;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestParser;
import me.prexorjustin.prexorcloud.protocol.DaemonMessage;
import me.prexorjustin.prexorcloud.protocol.ModuleInstall;
import me.prexorjustin.prexorcloud.protocol.ModuleStateUpdate;
import me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daemon-side manager for {@link DaemonModule} install / uninstall / lifecycle.
 *
 * <p>Receives {@link ModuleInstall} / {@link me.prexorjustin.prexorcloud.protocol.ModuleUninstall}
 * messages from the controller, persists the jar via {@link DaemonModuleStore}, opens an
 * isolated classloader, instantiates the {@code backend.daemon.entrypoint}, and runs the
 * lifecycle through the lifted {@link ModuleLifecycleManager}.
 *
 * <p>Lifecycle hooks ({@code onLoad}/{@code onStart}/{@code onStop}/{@code onUnload}/
 * {@code onUpgrade}) are dispatched via a {@link DaemonModuleAdapter} so the lifecycle
 * manager — which only knows {@code PlatformModule} — drives a {@link DaemonModule}
 * through the same state machine. Instance hooks ({@code onInstanceStarting}, etc.) are
 * dispatched separately by {@link DaemonModuleHost} when the daemon's process layer fires
 * them (PR 7d wiring).
 *
 * <p>State transitions are reported back to the controller as {@link ModuleStateUpdate}
 * messages over the daemon's gRPC stream.
 */
public final class DaemonModuleManager {

    private static final Logger logger = LoggerFactory.getLogger(DaemonModuleManager.class);

    private static final Set<String> PARENT_PREFIXES = new LinkedHashSet<>(
            Set.of("java.", "javax.", "jdk.", "sun.", "org.slf4j.", "me.prexorjustin.prexorcloud.api."));

    private final DaemonModuleStore store;
    private final DaemonModuleHost moduleHost;
    private final CapabilityRegistry runtimeRegistry;
    private final ModuleLifecycleManager lifecycleManager;
    private final ModuleStateReporter stateReporter;
    private final PlatformModuleSignatureVerifier signatureVerifier;

    private final Map<String, RuntimeBinding> runtimes = new LinkedHashMap<>();

    private record RuntimeBinding(
            DaemonModuleStore.StoredModule storedModule,
            PlatformModuleManifest manifest,
            DaemonModule module,
            DaemonModuleAdapter adapter,
            URLClassLoader classLoader) {}

    /** Reports a module's daemon-side state back to the controller. */
    @FunctionalInterface
    public interface ModuleStateReporter {
        void report(String moduleId, String state, String lastError);
    }

    public DaemonModuleManager(
            DaemonModuleStore store,
            DaemonModuleHost moduleHost,
            DaemonCapabilityRegistryImpl capabilityRegistry,
            ModuleLifecycleManager.ContextFactory contextFactory,
            ModuleStateReporter stateReporter,
            PlatformModuleSignatureVerifier signatureVerifier) {
        this.store = Objects.requireNonNull(store, "store");
        this.moduleHost = Objects.requireNonNull(moduleHost, "moduleHost");
        this.runtimeRegistry =
                Objects.requireNonNull(capabilityRegistry, "capabilityRegistry").runtimeRegistry();
        this.stateReporter = stateReporter == null ? (_, _, _) -> {} : stateReporter;
        this.signatureVerifier = signatureVerifier == null ? PlatformModuleSignatureVerifier.NOOP : signatureVerifier;
        this.lifecycleManager = new ModuleLifecycleManager(
                runtimeRegistry,
                manifest -> PlatformModuleStorage.none(manifest.id(), manifest.storage()),
                me.prexorjustin.prexorcloud.modules.runtime.ModuleRouteRegistry.NOOP_HOOK,
                contextFactory == null ? ModuleLifecycleManager.noopContextFactory() : contextFactory);
    }

    /**
     * Apply an inbound {@link ModuleInstall}: persist the jar, open the runtime, and run
     * the lifecycle through to ACTIVE (or WAITING / FAILED). Idempotent on re-pushes of
     * the same {@code (moduleId, sha256)} pair.
     */
    public synchronized void install(ModuleInstall install) {
        Objects.requireNonNull(install, "install");
        String moduleId = install.getModuleId();
        try {
            PlatformModuleManifest manifest = parseManifest(install);
            if (!manifest.hosts().contains(ModuleHost.DAEMON)) {
                logger.debug("ignoring install for {} — manifest does not declare daemon host", moduleId);
                return;
            }
            if (manifest.backend().daemon() == null) {
                throw new IllegalStateException(
                        "module '" + moduleId + "' declares daemon host but has no backend.daemon entrypoint");
            }
            byte[] jarBytes = install.getJarBytes().toByteArray();
            byte[] sidecarBytes = install.getSignatureBytes().toByteArray();
            String sidecarKind = install.getSignatureKind();
            verifySignature(install, manifest, jarBytes, sidecarBytes, sidecarKind);
            DaemonModuleStore.StoredModule stored = store.commit(
                    moduleId, install.getVersion(), install.getSha256(), jarBytes, sidecarBytes, sidecarKind);

            RuntimeBinding existing = runtimes.get(moduleId);
            URLClassLoader classLoader = openClassLoader(stored.jarPath());
            DaemonModule module;
            try {
                module = instantiateEntrypoint(manifest, classLoader);
            } catch (Exception e) {
                closeQuietly(classLoader);
                throw e;
            }
            DaemonModuleAdapter adapter = new DaemonModuleAdapter(module);
            RuntimeBinding binding = new RuntimeBinding(stored, manifest, module, adapter, classLoader);

            ModuleLifecycleManager.ManagedModule managed;
            if (existing == null) {
                managed = lifecycleManager.install(stored.jarPath(), manifest, adapter);
            } else {
                managed = lifecycleManager.upgrade(moduleId, stored.jarPath(), manifest, adapter);
            }

            runtimes.put(moduleId, binding);

            if (managed.state() == ModuleLifecycleManager.ModuleState.ACTIVE) {
                runtimeRegistry.activateModule(manifest, module.capabilityHandles());
                moduleHost.register(moduleId, module);
            } else if (managed.state() == ModuleLifecycleManager.ModuleState.WAITING) {
                moduleHost.unregister(moduleId);
            }

            if (existing != null) {
                closeQuietly(existing.classLoader());
            }

            reportState(moduleId, managed.state().name(), managed.lastError());
        } catch (Exception e) {
            logger.warn("failed to install daemon module {}: {}", moduleId, e.getMessage());
            reportState(moduleId, ModuleLifecycleManager.ModuleState.FAILED.name(), e.getMessage());
        }
    }

    /** Apply an inbound {@code ModuleUninstall}: stop the module and drop its runtime. */
    public synchronized void uninstall(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        RuntimeBinding binding = runtimes.remove(moduleId);
        moduleHost.unregister(moduleId);
        try {
            if (binding != null) {
                lifecycleManager.uninstall(moduleId);
            }
            store.remove(moduleId);
        } catch (Exception e) {
            logger.warn("failed to uninstall daemon module {}: {}", moduleId, e.getMessage());
            reportState(moduleId, ModuleLifecycleManager.ModuleState.FAILED.name(), e.getMessage());
            return;
        } finally {
            if (binding != null) {
                closeQuietly(binding.classLoader());
            }
        }
        reportState(moduleId, ModuleLifecycleManager.ModuleState.UNLOADED.name(), null);
    }

    /** Stop and drop every active module. Called by {@code PrexorDaemon.shutdown()}. */
    public synchronized void stopAll() {
        for (String moduleId : List.copyOf(runtimes.keySet())) {
            try {
                uninstall(moduleId);
            } catch (Exception e) {
                logger.warn("error stopping module {} during shutdown: {}", moduleId, e.getMessage());
            }
        }
    }

    /** Snapshot of currently-installed module ids. Mainly for tests. */
    public synchronized List<String> installedModuleIds() {
        return List.copyOf(runtimes.keySet());
    }

    /** Snapshot of a single module's lifecycle state, if installed. */
    public synchronized Optional<ModuleLifecycleManager.ModuleState> moduleState(String moduleId) {
        return lifecycleManager.find(moduleId).map(ModuleLifecycleManager.ManagedModule::state);
    }

    /**
     * Run the configured {@link PlatformModuleSignatureVerifier} against the inbound jar.
     * NOOP-fast-paths when the verifier is the {@code NOOP} singleton — we don't write
     * temp files for a verifier that won't read them. Otherwise, write jar + sidecar to a
     * temp directory as siblings (the on-disk shape both {@code TrustRootVerifier} and
     * {@code CosignBundleVerifier} expect) and call {@code verify}.
     */
    private void verifySignature(
            ModuleInstall install,
            PlatformModuleManifest manifest,
            byte[] jarBytes,
            byte[] sidecarBytes,
            String sidecarKind)
            throws IOException {
        if (signatureVerifier == PlatformModuleSignatureVerifier.NOOP) {
            return;
        }
        java.nio.file.Path tmpDir = Files.createTempDirectory("prexor-daemon-module-verify-");
        try {
            String safeName = install.getModuleId().replaceAll("[^a-zA-Z0-9._-]", "_");
            java.nio.file.Path jarFile = tmpDir.resolve(safeName + ".jar");
            Files.write(jarFile, jarBytes);
            if (sidecarBytes != null && sidecarBytes.length > 0 && sidecarKind != null && !sidecarKind.isBlank()) {
                String suffix =
                        switch (sidecarKind) {
                            case "sig" -> ".sig";
                            case "cosign-bundle" -> ".cosign.bundle";
                            default -> throw new IllegalStateException("unknown sidecar kind: " + sidecarKind);
                        };
                Files.write(jarFile.resolveSibling(jarFile.getFileName() + suffix), sidecarBytes);
            }
            signatureVerifier.verify(new PlatformModuleSignatureVerifier.VerificationInput(
                    jarFile, install.getModuleId(), install.getVersion(), install.getSha256()));
        } finally {
            // Best-effort cleanup; failure here is not fatal.
            try (var stream = Files.list(tmpDir)) {
                stream.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException _) {
                    }
                });
            } catch (IOException _) {
            }
            try {
                Files.deleteIfExists(tmpDir);
            } catch (IOException _) {
            }
        }
    }

    private PlatformModuleManifest parseManifest(ModuleInstall install) throws IOException {
        String yaml = install.getManifestYaml();
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("ModuleInstall is missing manifest_yaml");
        }
        try (var input = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            return PlatformModuleManifestParser.parse(input, install.getModuleId());
        }
    }

    private static URLClassLoader openClassLoader(Path jarPath) {
        try {
            FilteringParentClassLoader parent =
                    new FilteringParentClassLoader(DaemonModule.class.getClassLoader(), PARENT_PREFIXES);
            return new URLClassLoader(new URL[] {jarPath.toUri().toURL()}, parent);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("failed to open module jar " + jarPath, e));
        }
    }

    private static DaemonModule instantiateEntrypoint(PlatformModuleManifest manifest, ClassLoader classLoader)
            throws Exception {
        String entrypointFqcn = manifest.backend().daemon().entrypoint();
        Class<?> entrypointType = Class.forName(entrypointFqcn, true, classLoader);
        if (!DaemonModule.class.isAssignableFrom(entrypointType)) {
            throw new IllegalStateException(
                    "backend.daemon entrypoint does not implement DaemonModule: " + entrypointFqcn);
        }
        return DaemonModule.class.cast(entrypointType.getDeclaredConstructor().newInstance());
    }

    private void reportState(String moduleId, String state, String lastError) {
        try {
            stateReporter.report(moduleId, state, lastError == null ? "" : lastError);
        } catch (Exception e) {
            logger.debug("state reporter threw for {}: {}", moduleId, e.getMessage());
        }
    }

    private static void closeQuietly(URLClassLoader classLoader) {
        if (classLoader == null) return;
        try {
            classLoader.close();
        } catch (IOException _) {
        }
    }

    /**
     * Convenience helper for the daemon to wire a {@link ModuleStateReporter} that pushes
     * {@link ModuleStateUpdate} messages back to the controller via the daemon's gRPC client.
     */
    public static ModuleStateReporter reporterTo(java.util.function.Consumer<DaemonMessage> sender) {
        return (moduleId, state, lastError) -> sender.accept(DaemonMessage.newBuilder()
                .setModuleStateUpdate(ModuleStateUpdate.newBuilder()
                        .setModuleId(moduleId)
                        .setState(state)
                        .setLastError(lastError == null ? "" : lastError)
                        .setUpdatedAtMs(System.currentTimeMillis()))
                .build());
    }

    private static final class FilteringParentClassLoader extends ClassLoader {

        private final ClassLoader delegate;
        private final Set<String> allowedPrefixes;

        private FilteringParentClassLoader(ClassLoader delegate, Set<String> allowedPrefixes) {
            super(null);
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.allowedPrefixes = Set.copyOf(allowedPrefixes);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> alreadyLoaded = findLoadedClass(name);
                if (alreadyLoaded != null) {
                    return alreadyLoaded;
                }
                if (!isAllowed(name)) {
                    throw new ClassNotFoundException(name);
                }
                Class<?> loaded = delegate.loadClass(name);
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private boolean isAllowed(String className) {
            for (String prefix : allowedPrefixes) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
