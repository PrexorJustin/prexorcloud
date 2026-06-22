package me.prexorjustin.prexorcloud.controller.module.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleLifecycleManager;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestParser;
import me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PlatformModuleManager")
class PlatformModuleManagerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("activates waiting consumers when a provider is installed and injects the resolved handle")
    void activatesWaitingConsumersWithInjectedHandle() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        AtomicReference<String> resolvedProfile = new AtomicReference<>();
        RecordingPlatformModule provider = new RecordingPlatformModule(Map.of("player-profile", "profile-service"));
        RecordingPlatformModule consumer = new RecordingPlatformModule(Map.of(), resolvedProfile);

        PlatformModuleManager manager =
                new PlatformModuleManager(store, storedModule -> switch (storedModule.moduleId()) {
                    case "profile" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(storedModule.manifest(), provider, () -> {});
                    case "queue" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(storedModule.manifest(), consumer, () -> {});
                    default -> throw new IllegalArgumentException("unexpected module: " + storedModule.moduleId());
                });

        manager.install(createModuleJar(tempDir.resolve("queue.jar"), consumerManifestYaml()));
        PlatformModuleManager.ManagedPlatformModule waiting =
                manager.snapshot("queue").orElseThrow();
        assertEquals(ModuleLifecycleManager.ModuleState.WAITING, waiting.state());

        manager.install(createModuleJar(tempDir.resolve("profile.jar"), providerManifestYaml()));
        PlatformModuleManager.ManagedPlatformModule activated =
                manager.snapshot("queue").orElseThrow();

        assertEquals(ModuleLifecycleManager.ModuleState.ACTIVE, activated.state());
        assertEquals("profile-service", resolvedProfile.get());
        assertEquals(List.of("load", "start"), consumer.invocations());
    }

    @Test
    @DisplayName("provider uninstall moves dependent modules back to waiting")
    void uninstallMovesDependentsBackToWaiting() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        RecordingPlatformModule provider = new RecordingPlatformModule(Map.of("player-profile", "profile-service"));
        RecordingPlatformModule consumer = new RecordingPlatformModule(Map.of(), new AtomicReference<>());

        PlatformModuleManager manager =
                new PlatformModuleManager(store, storedModule -> switch (storedModule.moduleId()) {
                    case "profile" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(storedModule.manifest(), provider, () -> {});
                    case "queue" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(storedModule.manifest(), consumer, () -> {});
                    default -> throw new IllegalArgumentException("unexpected module: " + storedModule.moduleId());
                });

        manager.install(createModuleJar(tempDir.resolve("profile.jar"), providerManifestYaml()));
        manager.install(createModuleJar(tempDir.resolve("queue.jar"), consumerManifestYaml()));
        manager.uninstall("profile");

        PlatformModuleManager.ManagedPlatformModule consumerState =
                manager.snapshot("queue").orElseThrow();
        assertEquals(ModuleLifecycleManager.ModuleState.WAITING, consumerState.state());
        assertEquals(List.of("load", "start", "stop"), consumer.invocations());
    }

    @Test
    @DisplayName("provider upgrade hot-swaps interface capability handles without restarting consumers")
    void providerUpgradeHotSwapsCapabilityWithoutRestartingConsumer() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        SupplierTrackingPlatformModule consumer = new SupplierTrackingPlatformModule();
        RecordingPlatformModule providerV1 =
                new RecordingPlatformModule(Map.of("player-profile", (Supplier<String>) () -> "profile-v1"));
        RecordingPlatformModule providerV2 =
                new RecordingPlatformModule(Map.of("player-profile", (Supplier<String>) () -> "profile-v2"));

        PlatformModuleManager manager = new PlatformModuleManager(
                store, storedModule -> switch (storedModule.moduleId() + "@" + storedModule.version()) {
                    case "profile@1.0.0" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(storedModule.manifest(), providerV1, () -> {});
                    case "profile@1.1.0" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(storedModule.manifest(), providerV2, () -> {});
                    case "queue@1.0.0" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(storedModule.manifest(), consumer, () -> {});
                    default ->
                        throw new IllegalArgumentException(
                                "unexpected module: " + storedModule.moduleId() + "@" + storedModule.version());
                });

        manager.install(createModuleJar(tempDir.resolve("profile-v1.jar"), providerManifestYaml("1.0.0")));
        manager.install(createModuleJar(tempDir.resolve("queue.jar"), consumerManifestYaml()));

        assertEquals("profile-v1", consumer.currentProfile());
        assertEquals(List.of("load", "start"), consumer.invocations());

        manager.install(createModuleJar(tempDir.resolve("profile-v2.jar"), providerManifestYaml("1.1.0")));

        assertEquals(
                ModuleLifecycleManager.ModuleState.ACTIVE,
                manager.snapshot("queue").orElseThrow().state());
        assertEquals("profile-v2", consumer.currentProfile());
        assertEquals(List.of("load", "start"), consumer.invocations());
    }

    @Test
    @DisplayName("a reloadable module reinstall takes the RELOADING fast path instead of upgrade")
    void reloadableModuleReinstallTakesFastPath() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        RecordingPlatformModule v1 = new RecordingPlatformModule(Map.of());
        RecordingPlatformModule v2 = new RecordingPlatformModule(Map.of());

        PlatformModuleManager manager =
                new PlatformModuleManager(store, storedModule -> switch (storedModule.version()) {
                    case "1.0.0" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(storedModule.manifest(), v1, () -> {});
                    case "2.0.0" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(storedModule.manifest(), v2, () -> {});
                    default -> throw new IllegalArgumentException("unexpected version: " + storedModule.version());
                });

        manager.install(createModuleJar(tempDir.resolve("chat-1.jar"), reloadableManifestYaml("1.0.0")));
        assertEquals(List.of("load", "start"), v1.invocations());

        manager.install(createModuleJar(tempDir.resolve("chat-2.jar"), reloadableManifestYaml("2.0.0")));

        PlatformModuleManager.ManagedPlatformModule reloaded =
                manager.snapshot("chat").orElseThrow();
        assertEquals(ModuleLifecycleManager.ModuleState.ACTIVE, reloaded.state());
        assertEquals("2.0.0", reloaded.version());
        // Fast path: the predecessor is never stopped or unloaded.
        assertEquals(List.of("load", "start"), v1.invocations());
        // The new entrypoint sees only onReload.
        assertEquals(List.of("reload"), v2.invocations());
    }

    @Test
    @DisplayName("reconcileStoredModules loads modules installed by another controller")
    void reconcileStoredModulesLoadsModulesInstalledByAnotherController() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        RecordingPlatformModule providerPrimary = new RecordingPlatformModule(Map.of("player-profile", "profile-v1"));
        RecordingPlatformModule consumerPrimary = new RecordingPlatformModule(Map.of(), new AtomicReference<>());
        RecordingPlatformModule consumerStandby = new RecordingPlatformModule(Map.of(), new AtomicReference<>());
        RecordingPlatformModule providerStandby = new RecordingPlatformModule(Map.of("player-profile", "profile-v1"));

        PlatformModuleManager primary =
                new PlatformModuleManager(store, storedModule -> switch (storedModule.moduleId()) {
                    case "profile" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(), providerPrimary, () -> {});
                    case "queue" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(), consumerPrimary, () -> {});
                    default -> throw new IllegalArgumentException("unexpected module: " + storedModule.moduleId());
                });
        PlatformModuleManager standby =
                new PlatformModuleManager(store, storedModule -> switch (storedModule.moduleId()) {
                    case "profile" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(), providerStandby, () -> {});
                    case "queue" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(), consumerStandby, () -> {});
                    default -> throw new IllegalArgumentException("unexpected module: " + storedModule.moduleId());
                });
        standby.loadStoredModules();

        primary.install(createModuleJar(tempDir.resolve("profile.jar"), providerManifestYaml()));
        primary.install(createModuleJar(tempDir.resolve("queue.jar"), consumerManifestYaml()));

        assertTrue(standby.reconcileStoredModules());
        assertEquals(
                ModuleLifecycleManager.ModuleState.ACTIVE,
                standby.snapshot("profile").orElseThrow().state());
        assertEquals(
                ModuleLifecycleManager.ModuleState.ACTIVE,
                standby.snapshot("queue").orElseThrow().state());
        assertEquals(List.of("load", "start"), providerStandby.invocations());
        assertEquals(List.of("load", "start"), consumerStandby.invocations());
    }

    @Test
    @DisplayName("reconcileStoredModules unloads modules removed by another controller")
    void reconcileStoredModulesUnloadsModulesRemovedByAnotherController() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        RecordingPlatformModule providerPrimary = new RecordingPlatformModule(Map.of("player-profile", "profile-v1"));
        RecordingPlatformModule providerStandby = new RecordingPlatformModule(Map.of("player-profile", "profile-v1"));
        RecordingPlatformModule consumerPrimary = new RecordingPlatformModule(Map.of(), new AtomicReference<>());
        RecordingPlatformModule consumerStandby = new RecordingPlatformModule(Map.of(), new AtomicReference<>());

        PlatformModuleManager primary =
                new PlatformModuleManager(store, storedModule -> switch (storedModule.moduleId()) {
                    case "profile" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(), providerPrimary, () -> {});
                    case "queue" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(), consumerPrimary, () -> {});
                    default -> throw new IllegalArgumentException("unexpected module: " + storedModule.moduleId());
                });
        PlatformModuleManager standby =
                new PlatformModuleManager(store, storedModule -> switch (storedModule.moduleId()) {
                    case "profile" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(), providerStandby, () -> {});
                    case "queue" ->
                        new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(), consumerStandby, () -> {});
                    default -> throw new IllegalArgumentException("unexpected module: " + storedModule.moduleId());
                });

        primary.install(createModuleJar(tempDir.resolve("profile.jar"), providerManifestYaml()));
        primary.install(createModuleJar(tempDir.resolve("queue.jar"), consumerManifestYaml()));
        standby.loadStoredModules();

        assertEquals(
                ModuleLifecycleManager.ModuleState.ACTIVE,
                standby.snapshot("queue").orElseThrow().state());

        primary.uninstall("profile");

        assertTrue(standby.reconcileStoredModules());
        assertFalse(standby.snapshot("profile").isPresent());
        assertEquals(
                ModuleLifecycleManager.ModuleState.WAITING,
                standby.snapshot("queue").orElseThrow().state());
        assertEquals(List.of("load", "start", "stop"), providerStandby.invocations());
        assertEquals(List.of("load", "start", "stop"), consumerStandby.invocations());
    }

    @Test
    @DisplayName("storage can only be dropped after the module is uninstalled")
    void dropStorageRequiresUninstalledModule() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        PlatformModuleManager manager = new PlatformModuleManager(
                store,
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(), new RecordingPlatformModule(Map.of()), () -> {}),
                new PlatformModuleStorageManager(
                        null,
                        null,
                        new me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices(),
                        new com.fasterxml.jackson.databind.ObjectMapper()));

        manager.install(createModuleJar(tempDir.resolve("chat.jar"), consumerManifestYaml()));

        assertThrows(IllegalStateException.class, () -> manager.dropStorage("queue"));

        manager.uninstall("queue");
        PlatformModuleStorageManager.StorageDropResult dropped = manager.dropStorage("queue");
        assertEquals(0, dropped.mongoCollectionsDropped());
    }

    @Test
    @DisplayName("install is rejected on a non-leader controller (ownership = leadership)")
    void installRejectedOnFollower() {
        PlatformModuleManager manager = new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve("store")),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(), new RecordingPlatformModule(Map.of()), () -> {}),
                new PlatformModuleStorageManager(
                        null,
                        null,
                        new me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices(),
                        new com.fasterxml.jackson.databind.ObjectMapper()));
        manager.setLeadership(notLeader());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> manager.install(createModuleJar(tempDir.resolve("queue.jar"), consumerManifestYaml())));
        assertTrue(error.getMessage().contains("leader"));
        assertTrue(manager.listModules().isEmpty());
    }

    @Test
    @DisplayName("install invokes the signature verification hook before storing a module")
    void installInvokesSignatureVerifierBeforeCommit() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        AtomicReference<PlatformModuleSignatureVerifier.VerificationInput> verified = new AtomicReference<>();
        PlatformModuleManager manager = new PlatformModuleManager(
                store,
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(), new RecordingPlatformModule(Map.of()), () -> {}),
                new PlatformModuleStorageManager(
                        null,
                        null,
                        new me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices(),
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                verified::set);

        PlatformModuleManager.ManagedPlatformModule installed =
                manager.install(createModuleJar(tempDir.resolve("queue.jar"), consumerManifestYaml()));

        assertEquals("queue", installed.moduleId());
        assertNotNull(verified.get());
        assertEquals("queue", verified.get().moduleId());
        assertEquals(1, store.list().size());
    }

    @Test
    @DisplayName("signature verification failure aborts before store commit")
    void signatureVerifierFailureAbortsInstallBeforeCommit() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        PlatformModuleManager manager = new PlatformModuleManager(
                store,
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(), new RecordingPlatformModule(Map.of()), () -> {}),
                new PlatformModuleStorageManager(
                        null,
                        null,
                        new me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices(),
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                preparedModule -> {
                    throw new IllegalStateException("signature rejected");
                });

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> manager.install(createModuleJar(tempDir.resolve("queue.jar"), consumerManifestYaml())));

        assertTrue(error.getMessage().contains("signature rejected"));
        assertTrue(store.list().isEmpty());
        assertTrue(manager.listModules().isEmpty());
    }

    @Test
    @DisplayName("uninstall is rejected on a non-leader controller (ownership = leadership)")
    void uninstallRejectedOnFollower() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        PlatformModuleRuntimeFactory runtimeFactory = storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                storedModule.manifest(),
                new RecordingPlatformModule(Map.of("player-profile", "profile-service")),
                () -> {});

        PlatformModuleManager installer = new PlatformModuleManager(store, runtimeFactory);
        installer.install(createModuleJar(tempDir.resolve("profile.jar"), providerManifestYaml()));
        installer.close();

        PlatformModuleManager manager = new PlatformModuleManager(
                store,
                runtimeFactory,
                new PlatformModuleStorageManager(
                        null,
                        null,
                        new me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices(),
                        new com.fasterxml.jackson.databind.ObjectMapper()));
        manager.setLeadership(notLeader());
        manager.loadStoredModules();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> manager.uninstall("profile"));

        assertTrue(error.getMessage().contains("leader"));
        assertTrue(manager.snapshot("profile").isPresent());
    }

    private static Leadership notLeader() {
        return new Leadership() {
            @Override
            public boolean isLeader() {
                return false;
            }

            @Override
            public long currentEpoch() {
                return 0L;
            }
        };
    }

    private static Path createModuleJar(Path jarPath, String manifest) {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath))) {
            out.putNextEntry(new JarEntry(PlatformModuleManifestParser.FILE_NAME));
            out.write(manifest.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            return jarPath;
        } catch (IOException e) {
            throw new IllegalStateException("failed to create test module jar", e);
        }
    }

    private static String providerManifestYaml() {
        return providerManifestYaml("1.0.0");
    }

    private static String providerManifestYaml(String version) {
        return """
                manifestVersion: 1
                id: profile
                version: %s
                backend:
                  entrypoint: example.ProfileModule
                capabilities:
                  provides:
                    - id: player-profile
                      version: %s
                """.formatted(version, version);
    }

    private static String consumerManifestYaml() {
        return """
                manifestVersion: 1
                id: queue
                version: 1.0.0
                backend:
                  entrypoint: example.QueueModule
                capabilities:
                  requires:
                    - id: player-profile
                      versionRange: "[1.0,2.0)"
                """;
    }

    private static String reloadableManifestYaml(String version) {
        return """
                manifestVersion: 2
                id: chat
                version: %s
                backend:
                  controller:
                    entrypoint: example.ChatModule
                    reloadable: true
                """.formatted(version);
    }

    private static final class RecordingPlatformModule implements PlatformModule {

        private final List<String> invocations = new java.util.ArrayList<>();
        private final List<CapabilityHandle<?>> capabilityHandles;
        private final AtomicReference<String> resolvedProfile;

        private RecordingPlatformModule(Map<String, Object> capabilityHandles) {
            this(capabilityHandles, new AtomicReference<>());
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private RecordingPlatformModule(
                Map<String, Object> capabilityHandles, AtomicReference<String> resolvedProfile) {
            List<CapabilityHandle<?>> handles = new java.util.ArrayList<>();
            for (Map.Entry<String, Object> entry : capabilityHandles.entrySet()) {
                handles.add(CapabilityHandle.of(
                        entry.getKey(), (Class) entry.getValue().getClass(), entry.getValue()));
            }
            this.capabilityHandles = List.copyOf(handles);
            this.resolvedProfile = resolvedProfile;
        }

        @Override
        public void onLoad(ModuleContext context) {
            invocations.add("load");
        }

        @Override
        public void onStart(ModuleContext context) {
            invocations.add("start");
            context.findCapability("player-profile", String.class).ifPresent(resolvedProfile::set);
        }

        @Override
        public void onStop(ModuleContext context) {
            invocations.add("stop");
        }

        @Override
        public void onReload(ModuleContext context) {
            invocations.add("reload");
        }

        @Override
        public List<CapabilityHandle<?>> capabilityHandles() {
            return capabilityHandles;
        }

        private List<String> invocations() {
            return List.copyOf(invocations);
        }
    }

    private static final class SupplierTrackingPlatformModule implements PlatformModule {

        private final List<String> invocations = new java.util.ArrayList<>();
        private Supplier<String> profileSupplier;

        @Override
        public void onLoad(ModuleContext context) {
            invocations.add("load");
        }

        @Override
        public void onStart(ModuleContext context) {
            invocations.add("start");
            profileSupplier = context.requireCapability("player-profile", Supplier.class);
        }

        @Override
        public void onStop(ModuleContext context) {
            invocations.add("stop");
        }

        private String currentProfile() {
            return profileSupplier.get();
        }

        private List<String> invocations() {
            return List.copyOf(invocations);
        }
    }
}
