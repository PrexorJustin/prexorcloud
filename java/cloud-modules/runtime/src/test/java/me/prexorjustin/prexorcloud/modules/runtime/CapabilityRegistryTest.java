package me.prexorjustin.prexorcloud.modules.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import me.prexorjustin.prexorcloud.api.module.platform.CapabilityDeclaration;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CapabilityRegistry")
class CapabilityRegistryTest {

    @Test
    @DisplayName("resolves satisfied requirements against an active provider")
    void resolvesSatisfiedRequirements() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.activateModule(
                provider("profile", "player-profile", "1.2.0"),
                List.of(CapabilityHandle.of("player-profile", String.class, "handle")));

        assertTrue(registry.requirementsSatisfied(consumer("queue", "player-profile", "[1.0,2.0)")));
        assertEquals("profile", registry.find("player-profile").orElseThrow().moduleId());
    }

    @Test
    @DisplayName("reports missing providers and version mismatches")
    void reportsMissingProvidersAndVersionMismatches() {
        CapabilityRegistry registry = new CapabilityRegistry();
        PlatformModuleManifest consumer = consumer("queue", "player-profile", "[2.0,3.0)");

        List<CapabilityRegistry.UnresolvedRequirement> missing = registry.unresolvedRequirements(consumer);
        assertEquals(1, missing.size());
        assertTrue(missing.getFirst().reason().contains("missing"));

        registry.activateModule(provider("profile", "player-profile", "1.2.0"), List.of());
        List<CapabilityRegistry.UnresolvedRequirement> mismatch = registry.unresolvedRequirements(consumer);
        assertEquals(1, mismatch.size());
        assertTrue(mismatch.getFirst().reason().contains("version mismatch"));
    }

    @Test
    @DisplayName("allows rebinding after provider removal")
    void allowsRebindingAfterProviderRemoval() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.activateModule(provider("profile-v1", "player-profile", "1.0.0"), List.of());
        registry.deactivateModule("profile-v1");
        registry.activateModule(provider("profile-v2", "player-profile", "1.1.0"), List.of());

        CapabilityRegistry.CapabilityBinding binding =
                registry.find("player-profile").orElseThrow();
        assertEquals("profile-v2", binding.moduleId());
        assertTrue(registry.metrics().rebindingEventCount() >= 2);
    }

    @Test
    @DisplayName("clears the dynamic handle proxy cache on provider deactivation")
    void clearsProxyCacheOnDeactivation() {
        CapabilityRegistry registry = new CapabilityRegistry();
        PlatformModuleManifest provider = provider("profile", "player-profile", "1.0.0");
        registry.activateModule(provider, List.of(CapabilityHandle.of("player-profile", Runnable.class, () -> {})));
        registry.resolveRequiredHandles(consumer("queue", "player-profile", "[1.0,2.0)"));

        Object dynamicHandle = registry.find("player-profile").orElseThrow().handle();
        assertNotNull(dynamicHandle);

        // Force a proxy entry to be cached.
        ((me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandleResolver)
                        registry.resolveRequiredHandles(consumer("queue", "player-profile", "[1.0,2.0)"))
                                .get("player-profile"))
                .resolve(Runnable.class);
        assertEquals(1, registry.dynamicHandleProxyCacheSizeForTesting("player-profile"));
        assertNotNull(registry.dynamicHandleDelegateForTesting("player-profile"));

        registry.deactivateModule("profile");

        assertNull(registry.dynamicHandleDelegateForTesting("player-profile"));
        assertEquals(
                0,
                registry.dynamicHandleProxyCacheSizeForTesting("player-profile"),
                "proxies cache must be cleared so cached Class<?> keys do not pin a deactivated module's classloader");
    }

    @Test
    @DisplayName("clears proxy cache when replaceModuleBindings drops a capability")
    void clearsProxyCacheWhenCapabilityDropped() {
        CapabilityRegistry registry = new CapabilityRegistry();
        PlatformModuleManifest first = providerWithTwoCapabilities("profile");
        registry.activateModule(
                first,
                List.of(
                        CapabilityHandle.of("player-profile", Runnable.class, () -> {}),
                        CapabilityHandle.of("player-stats", Runnable.class, () -> {})));

        ((me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandleResolver)
                        registry.resolveRequiredHandles(consumer("queue", "player-stats", "[1.0,2.0)"))
                                .get("player-stats"))
                .resolve(Runnable.class);
        assertEquals(1, registry.dynamicHandleProxyCacheSizeForTesting("player-stats"));

        // Re-activate without player-stats — that capability must be cleaned up.
        registry.replaceModuleBindings(
                provider("profile", "player-profile", "1.0.0"),
                List.of(CapabilityHandle.of("player-profile", Runnable.class, () -> {})));

        assertNull(registry.dynamicHandleDelegateForTesting("player-stats"));
        assertEquals(0, registry.dynamicHandleProxyCacheSizeForTesting("player-stats"));
    }

    @Test
    @DisplayName("rejects duplicate active providers for the same capability")
    void rejectsDuplicateActiveProviders() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.activateModule(provider("profile-v1", "player-profile", "1.0.0"), List.of());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> registry.activateModule(provider("profile-v2", "player-profile", "1.1.0"), List.of()));
        assertTrue(ex.getMessage().contains("already provided"));
    }

    @Test
    @DisplayName("detects capability dependency cycles")
    void detectsCapabilityCycles() {
        CapabilityRegistry registry = new CapabilityRegistry();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> registry.validateNoCycles(List.of(
                        providerWithRequirement("a", "cap-a", "1.0.0", "cap-b", "[1.0,2.0)"),
                        providerWithRequirement("b", "cap-b", "1.0.0", "cap-a", "[1.0,2.0)"))));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    @DisplayName("rejects duplicate providers in the static capability graph")
    void rejectsDuplicateProvidersInGraph() {
        CapabilityRegistry registry = new CapabilityRegistry();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> registry.validateNoCycles(
                        List.of(provider("a", "player-profile", "1.0.0"), provider("b", "player-profile", "1.1.0"))));
        assertTrue(ex.getMessage().contains("multiple modules"));
    }

    @Test
    @DisplayName("registers a built-in handle that satisfies module requirements")
    void registersBuiltinHandle() {
        CapabilityRegistry registry = new CapabilityRegistry();
        Runnable handle = () -> {};
        registry.registerBuiltinHandle("prexor.player.journey", "1.0.0", handle);

        CapabilityRegistry.CapabilityBinding binding =
                registry.find("prexor.player.journey").orElseThrow();
        assertEquals(CapabilityRegistry.BUILTIN_PROVIDER_ID, binding.moduleId());
        assertSame(handle, binding.handle());
        assertTrue(registry.requirementsSatisfied(consumer("queue", "prexor.player.journey", "[1.0,2.0)")));
    }

    @Test
    @DisplayName("rejects modules trying to override a built-in capability")
    void rejectsOverrideOfBuiltinCapability() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.registerBuiltinHandle("prexor.player.journey", "1.0.0", new Object());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> registry.activateModule(provider("hijacker", "prexor.player.journey", "1.0.0"), List.of()));
        assertTrue(ex.getMessage().contains("already provided"));
    }

    @Test
    @DisplayName("flags resolutions that land on a deprecated provider")
    void flagsDeprecatedProviderResolutions() {
        CapabilityRegistry registry = new CapabilityRegistry();
        PlatformModuleManifest deprecatedProvider = new PlatformModuleManifest(
                2,
                "profile-legacy",
                "1.0.0",
                new PlatformModuleManifest.Backend("com.example.ProfileLegacy"),
                null,
                new CapabilityDeclaration(
                        List.of(new CapabilityDeclaration.Provides("player-profile", "1.4.0", "1.3.0", "2.0.0")),
                        List.of()),
                null,
                List.of());
        registry.activateModule(deprecatedProvider, List.of());

        CapabilityRegistry.CapabilityBinding binding =
                registry.find("player-profile").orElseThrow();
        assertTrue(binding.isDeprecated());
        assertEquals("1.3.0", binding.deprecatedSince());
        assertEquals("2.0.0", binding.removedIn());

        // A live requirement against the deprecated provider still resolves —
        // we only emit a warning + bump the deprecated-resolution counter.
        long before = registry.metrics().deprecatedProviderResolutionCount();
        registry.resolveRequiredHandles(consumer("queue", "player-profile", "[1.0,2.0)"));
        long after = registry.metrics().deprecatedProviderResolutionCount();
        assertEquals(before + 1, after);

        // Non-deprecated provider: counter stays put.
        registry.deactivateModule("profile-legacy");
        registry.activateModule(provider("profile-fresh", "player-profile", "1.5.0"), List.of());
        long beforeFresh = registry.metrics().deprecatedProviderResolutionCount();
        registry.resolveRequiredHandles(consumer("queue", "player-profile", "[1.0,2.0)"));
        assertEquals(beforeFresh, registry.metrics().deprecatedProviderResolutionCount());
    }

    @Test
    @DisplayName("rejects re-registering a built-in capability twice")
    void rejectsDuplicateBuiltinRegistration() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.registerBuiltinHandle("prexor.player.journey", "1.0.0", new Object());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> registry.registerBuiltinHandle("prexor.player.journey", "1.0.0", new Object()));
        assertTrue(ex.getMessage().contains("already provided"));
    }

    private static PlatformModuleManifest provider(String moduleId, String capabilityId, String version) {
        return new PlatformModuleManifest(
                1,
                moduleId,
                "1.0.0",
                new PlatformModuleManifest.Backend("com.example." + moduleId),
                null,
                new CapabilityDeclaration(
                        List.of(new CapabilityDeclaration.Provides(capabilityId, version)), List.of()),
                null,
                List.of());
    }

    @Test
    @DisplayName("notifies listener on register/unregister/provider-changed")
    void notifiesListenerOnLifecycle() {
        CapabilityRegistry registry = new CapabilityRegistry();
        java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        registry.setListener(new CapabilityRegistry.Listener() {
            @Override
            public void onCapabilityRegistered(String capabilityId, String version, String moduleId) {
                events.add("REG " + capabilityId + " " + version + " " + moduleId);
            }

            @Override
            public void onCapabilityUnregistered(String capabilityId, String moduleId) {
                events.add("UNREG " + capabilityId + " " + moduleId);
            }

            @Override
            public void onCapabilityProviderChanged(
                    String capabilityId, String moduleId, String fromVersion, String toVersion) {
                events.add("CHG " + capabilityId + " " + moduleId + " " + fromVersion + "->" + toVersion);
            }
        });

        // First activation → REGISTERED.
        registry.activateModule(provider("profile-v1", "player-profile", "1.0.0"), List.of());
        // Re-activate with same id and same version → no event.
        registry.activateModule(provider("profile-v1", "player-profile", "1.0.0"), List.of());
        // Replace bindings with a higher version → PROVIDER_CHANGED.
        registry.replaceModuleBindings(provider("profile-v1", "player-profile", "1.1.0"), List.of());
        // Deactivate → UNREGISTERED.
        registry.deactivateModule("profile-v1");
        // Built-in handle → REGISTERED with the @controller sentinel module id.
        registry.registerBuiltinHandle("prexor.player.journey", "1.0.0", "handle");

        assertEquals(
                List.of(
                        "REG player-profile 1.0.0 profile-v1",
                        "CHG player-profile profile-v1 1.0.0->1.1.0",
                        "UNREG player-profile profile-v1",
                        "REG prexor.player.journey 1.0.0 @controller"),
                events);
    }

    private static PlatformModuleManifest consumer(String moduleId, String capabilityId, String versionRange) {
        return new PlatformModuleManifest(
                1,
                moduleId,
                "1.0.0",
                new PlatformModuleManifest.Backend("com.example." + moduleId),
                null,
                new CapabilityDeclaration(
                        List.of(), List.of(new CapabilityDeclaration.Requires(capabilityId, versionRange))),
                null,
                List.of());
    }

    private static PlatformModuleManifest providerWithTwoCapabilities(String moduleId) {
        return new PlatformModuleManifest(
                1,
                moduleId,
                "1.0.0",
                new PlatformModuleManifest.Backend("com.example." + moduleId),
                null,
                new CapabilityDeclaration(
                        List.of(
                                new CapabilityDeclaration.Provides("player-profile", "1.0.0"),
                                new CapabilityDeclaration.Provides("player-stats", "1.0.0")),
                        List.of()),
                null,
                List.of());
    }

    private static PlatformModuleManifest providerWithRequirement(
            String moduleId,
            String providedCapability,
            String providedVersion,
            String requiredCapability,
            String range) {
        return new PlatformModuleManifest(
                1,
                moduleId,
                "1.0.0",
                new PlatformModuleManifest.Backend("com.example." + moduleId),
                null,
                new CapabilityDeclaration(
                        List.of(new CapabilityDeclaration.Provides(providedCapability, providedVersion)),
                        List.of(new CapabilityDeclaration.Requires(requiredCapability, range))),
                null,
                List.of());
    }
}
