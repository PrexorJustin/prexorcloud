package me.prexorjustin.prexorcloud.modules.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.module.platform.CapabilityDeclaration;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleHealth;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleStorage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("ModuleLifecycleManager")
class ModuleLifecycleManagerTest {

    @Test
    @DisplayName("installs and starts modules whose requirements are satisfied")
    void installsAndStartsSatisfiedModules() {
        ModuleLifecycleManager manager = new ModuleLifecycleManager(manifest -> true);
        RecordingModule module = new RecordingModule();

        ModuleLifecycleManager.ManagedModule managed =
                manager.install(Path.of("modules/chat.jar"), manifest("chat"), module);

        assertEquals(ModuleLifecycleManager.ModuleState.ACTIVE, managed.state());
        assertEquals(List.of("load", "start"), module.invocations());
    }

    @Test
    @DisplayName("keeps modules waiting until requirements resolve")
    void keepsWaitingUntilRequirementsResolve() {
        CapabilityRegistry capabilityRegistry = new CapabilityRegistry();
        ModuleLifecycleManager manager = new ModuleLifecycleManager(capabilityRegistry);
        RecordingModule module = new RecordingModule();

        ModuleLifecycleManager.ManagedModule installed =
                manager.install(Path.of("modules/queue.jar"), consumerManifest("queue"), module);

        assertEquals(ModuleLifecycleManager.ModuleState.WAITING, installed.state());
        assertEquals(List.of("load"), module.invocations());

        capabilityRegistry.activateModule(providerManifest("profile", "1.2.0"), List.of());
        ModuleLifecycleManager.ManagedModule activated = manager.reconcile("queue");

        assertEquals(ModuleLifecycleManager.ModuleState.ACTIVE, activated.state());
        assertEquals(List.of("load", "start"), module.invocations());
    }

    @Test
    @DisplayName("moves active modules back to waiting when requirements disappear")
    void movesActiveModulesBackToWaiting() {
        CapabilityRegistry capabilityRegistry = new CapabilityRegistry();
        capabilityRegistry.activateModule(providerManifest("profile", "1.2.0"), List.of());
        ModuleLifecycleManager manager = new ModuleLifecycleManager(capabilityRegistry);
        RecordingModule module = new RecordingModule();

        manager.install(Path.of("modules/queue.jar"), consumerManifest("queue"), module);
        capabilityRegistry.deactivateModule("profile");

        ModuleLifecycleManager.ManagedModule reconciled = manager.reconcile("queue");

        assertEquals(ModuleLifecycleManager.ModuleState.WAITING, reconciled.state());
        assertEquals(List.of("load", "start", "stop"), module.invocations());
    }

    @Test
    @DisplayName("marks modules failed when a lifecycle hook throws")
    void marksModulesFailedOnHookFailure() {
        ModuleLifecycleManager manager = new ModuleLifecycleManager(manifest -> true);
        RecordingModule module = new RecordingModule().failOn("start");

        ModuleLifecycleManager.ManagedModule managed =
                manager.install(Path.of("modules/failing.jar"), manifest("failing"), module);

        assertEquals(ModuleLifecycleManager.ModuleState.FAILED, managed.state());
        assertEquals("start failed", managed.lastError());
        assertEquals(List.of("load", "start"), module.invocations());
    }

    @Test
    @DisplayName("uninstalls active modules by stopping and unloading them")
    void uninstallsActiveModules() {
        ModuleLifecycleManager manager = new ModuleLifecycleManager(manifest -> true);
        RecordingModule module = new RecordingModule();

        manager.install(Path.of("modules/chat.jar"), manifest("chat"), module);
        ModuleLifecycleManager.ManagedModule managed = manager.uninstall("chat");

        assertEquals(ModuleLifecycleManager.ModuleState.UNLOADED, managed.state());
        assertEquals(List.of("load", "start", "stop", "unload"), module.invocations());
    }

    @Test
    @DisplayName("upgrades modules using previous version context")
    void upgradesModules() {
        ModuleLifecycleManager manager = new ModuleLifecycleManager(manifest -> true);
        RecordingModule v1 = new RecordingModule();
        RecordingModule v2 = new RecordingModule();

        manager.install(Path.of("modules/chat-1.jar"), manifest("chat", "1.0.0"), v1);
        ModuleLifecycleManager.ManagedModule upgraded =
                manager.upgrade("chat", Path.of("modules/chat-2.jar"), manifest("chat", "2.0.0"), v2);

        assertEquals(ModuleLifecycleManager.ModuleState.ACTIVE, upgraded.state());
        assertEquals(List.of("load", "start", "stop", "unload"), v1.invocations());
        assertEquals(List.of("load", "upgrade:1.0.0", "start"), v2.invocations());
    }

    @Test
    @DisplayName("reloads active reload-compatible modules without stopping the predecessor")
    void reloadsActiveCompatibleModules() {
        ModuleLifecycleManager manager = new ModuleLifecycleManager(manifest -> true);
        RecordingModule v1 = new RecordingModule();
        RecordingModule v2 = new RecordingModule();

        manager.install(Path.of("modules/chat-1.jar"), reloadableManifest("chat", "1.0.0"), v1);
        ModuleLifecycleManager.ManagedModule reloaded =
                manager.reload("chat", Path.of("modules/chat-2.jar"), reloadableManifest("chat", "2.0.0"), v2);

        assertEquals(ModuleLifecycleManager.ModuleState.ACTIVE, reloaded.state());
        assertEquals("2.0.0", reloaded.manifest().version());
        // The outgoing module is never stopped or unloaded on the fast path.
        assertEquals(List.of("load", "start"), v1.invocations());
        // The new entrypoint sees only onReload, carrying the predecessor's version.
        assertEquals(List.of("reload:1.0.0"), v2.invocations());
    }

    @Test
    @DisplayName("marks a module failed when onReload throws, with no rollback")
    void reloadFailsWhenOnReloadThrows() {
        ModuleLifecycleManager manager = new ModuleLifecycleManager(manifest -> true);
        manager.install(Path.of("modules/chat-1.jar"), reloadableManifest("chat", "1.0.0"), new RecordingModule());
        RecordingModule v2 = new RecordingModule().failOn("reload:1.0.0");

        ModuleLifecycleManager.ManagedModule reloaded =
                manager.reload("chat", Path.of("modules/chat-2.jar"), reloadableManifest("chat", "2.0.0"), v2);

        assertEquals(ModuleLifecycleManager.ModuleState.FAILED, reloaded.state());
        assertEquals("reload:1.0.0 failed", reloaded.lastError());
        assertEquals(List.of("reload:1.0.0"), v2.invocations());
    }

    @Test
    @DisplayName("reload rejects a module that is not ACTIVE")
    void reloadRejectsNonActiveModule() {
        CapabilityRegistry capabilityRegistry = new CapabilityRegistry();
        ModuleLifecycleManager manager = new ModuleLifecycleManager(capabilityRegistry);
        manager.install(Path.of("modules/queue.jar"), consumerManifest("queue"), new RecordingModule());

        assertThrows(
                IllegalStateException.class,
                () -> manager.reload(
                        "queue",
                        Path.of("modules/queue-2.jar"),
                        reloadableManifest("queue", "2.0.0"),
                        new RecordingModule()));
    }

    @Test
    @DisplayName("reload rejects a replacement that is not reload-compatible")
    void reloadRejectsIncompatibleReplacement() {
        ModuleLifecycleManager manager = new ModuleLifecycleManager(manifest -> true);
        manager.install(Path.of("modules/chat-1.jar"), reloadableManifest("chat", "1.0.0"), new RecordingModule());

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.reload(
                        "chat", Path.of("modules/chat-2.jar"), manifest("chat", "2.0.0"), new RecordingModule()));
    }

    @Test
    @DisplayName("reloadCompatible gates on the reloadable flag and an identical capability declaration")
    void reloadCompatibleStaticChecks() {
        assertTrue(ModuleLifecycleManager.reloadCompatible(
                reloadableManifest("chat", "1.0.0"), reloadableManifest("chat", "2.0.0")));
        // Replacement manifest did not opt in.
        assertFalse(ModuleLifecycleManager.reloadCompatible(
                reloadableManifest("chat", "1.0.0"), manifest("chat", "2.0.0")));
        // Opted in, but the capability declaration is not identical to the running version.
        assertFalse(ModuleLifecycleManager.reloadCompatible(
                providerManifest("chat", "1.0.0"), reloadableManifest("chat", "2.0.0")));
    }

    @Test
    @DisplayName("injects resolved storage handles into the platform module context")
    void injectsResolvedStorageHandles() {
        var mongoStore = Mockito.mock(me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore.class);
        Mockito.when(mongoStore.collectionPrefix()).thenReturn("platform_chat_");
        PlatformModuleStorage storage = new PlatformModuleStorage(
                "chat",
                new me.prexorjustin.prexorcloud.api.module.platform.ModuleStorageRequest(true),
                "prexorcloud",
                "platform_chat_",
                mongoStore);
        ModuleLifecycleManager manager = new ModuleLifecycleManager(manifest -> true, manifest -> storage);
        RecordingModule module = new RecordingModule();

        manager.install(Path.of("modules/chat.jar"), manifest("chat"), module);

        assertEquals(
                "platform_chat_", module.startContext().requireMongoStorage().collectionPrefix());
    }

    @Test
    @DisplayName("pollHealth returns each active module's self-reported health, default UNKNOWN")
    void pollHealthReportsActiveModules() {
        ModuleLifecycleManager manager = new ModuleLifecycleManager(manifest -> true);
        manager.install(Path.of("modules/chat.jar"), manifest("chat"), new PlatformModule() {
            @Override
            public ModuleHealth healthCheck() {
                return ModuleHealth.degraded("queue backlog");
            }
        });
        // A module that doesn't override healthCheck reports UNKNOWN.
        manager.install(Path.of("modules/quiet.jar"), manifest("quiet"), new RecordingModule());

        Map<String, ModuleHealth> health = manager.pollHealth();

        assertEquals(ModuleHealth.Status.DEGRADED, health.get("chat").status());
        assertEquals("queue backlog", health.get("chat").detail());
        assertEquals(ModuleHealth.Status.UNKNOWN, health.get("quiet").status());
    }

    @Test
    @DisplayName("pollHealth maps a throwing healthCheck to UNHEALTHY and skips non-active modules")
    void pollHealthHandlesThrowAndInactiveModules() {
        CapabilityRegistry capabilityRegistry = new CapabilityRegistry();
        ModuleLifecycleManager manager = new ModuleLifecycleManager(capabilityRegistry);
        // An ACTIVE module whose probe throws.
        manager.install(Path.of("modules/boom.jar"), manifest("boom"), new PlatformModule() {
            @Override
            public ModuleHealth healthCheck() {
                throw new IllegalStateException("backend unreachable");
            }
        });
        // A WAITING module (unsatisfied requirement) must NOT be polled.
        manager.install(Path.of("modules/queue.jar"), consumerManifest("queue"), new RecordingModule());

        Map<String, ModuleHealth> health = manager.pollHealth();

        assertEquals(
                ModuleLifecycleManager.ModuleState.WAITING,
                manager.find("queue").orElseThrow().state());
        assertEquals(ModuleHealth.Status.UNHEALTHY, health.get("boom").status());
        assertFalse(health.containsKey("queue"), "non-active modules are not polled");
    }

    private static PlatformModuleManifest manifest(String id) {
        return manifest(id, "1.0.0");
    }

    private static PlatformModuleManifest manifest(String id, String version) {
        return new PlatformModuleManifest(
                1,
                id,
                version,
                new PlatformModuleManifest.Backend("com.example." + id + ".Main"),
                null,
                CapabilityDeclaration.EMPTY,
                null,
                List.of());
    }

    private static PlatformModuleManifest reloadableManifest(String id, String version) {
        return new PlatformModuleManifest(
                2,
                id,
                version,
                new PlatformModuleManifest.Backend(
                        new PlatformModuleManifest.EntrypointSpec("com.example." + id + ".Main", true), null),
                null,
                CapabilityDeclaration.EMPTY,
                null,
                List.of());
    }

    private static PlatformModuleManifest providerManifest(String id, String version) {
        return new PlatformModuleManifest(
                1,
                id,
                version,
                new PlatformModuleManifest.Backend("com.example." + id + ".Main"),
                null,
                new CapabilityDeclaration(
                        List.of(new CapabilityDeclaration.Provides("player-profile", version)), List.of()),
                null,
                List.of());
    }

    private static PlatformModuleManifest consumerManifest(String id) {
        return new PlatformModuleManifest(
                1,
                id,
                "1.0.0",
                new PlatformModuleManifest.Backend("com.example." + id + ".Main"),
                null,
                new CapabilityDeclaration(
                        List.of(), List.of(new CapabilityDeclaration.Requires("player-profile", "[1.0,2.0)"))),
                null,
                List.of());
    }

    private static final class RecordingModule implements PlatformModule {

        private final List<String> invocations = new ArrayList<>();
        private String failOn;
        private ModuleContext startContext;

        RecordingModule failOn(String hook) {
            this.failOn = hook;
            return this;
        }

        List<String> invocations() {
            return List.copyOf(invocations);
        }

        @Override
        public void onLoad(ModuleContext context) throws Exception {
            invoke("load");
        }

        @Override
        public void onStart(ModuleContext context) throws Exception {
            startContext = context;
            invoke("start");
        }

        @Override
        public void onStop(ModuleContext context) throws Exception {
            invoke("stop");
        }

        @Override
        public void onUnload(ModuleContext context) throws Exception {
            invoke("unload");
        }

        @Override
        public void onUpgrade(ModuleContext context) throws Exception {
            invoke("upgrade:" + context.previousVersion());
        }

        @Override
        public void onReload(ModuleContext context) throws Exception {
            invoke("reload:" + context.previousVersion());
        }

        private void invoke(String hook) throws Exception {
            invocations.add(hook);
            if (hook.equals(failOn)) {
                throw new IllegalStateException(hook + " failed");
            }
        }

        private ModuleContext startContext() {
            return startContext;
        }
    }

}
