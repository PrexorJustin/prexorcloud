package me.prexorjustin.prexorcloud.controller.module.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import me.prexorjustin.prexorcloud.api.module.platform.ActivationPolicy;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.RuntimeTarget;
import me.prexorjustin.prexorcloud.api.module.platform.WorkloadExtensionManifest;
import me.prexorjustin.prexorcloud.api.module.platform.WorkloadExtensionVariant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExtensionRegistry")
class ExtensionRegistryTest {

    private static final RuntimeTarget PAPER = RuntimeTarget.parse("server/paper");

    @Test
    @DisplayName("lists logical extensions by target")
    void listsLogicalExtensionsByTarget() {
        ExtensionRegistry registry = new ExtensionRegistry(List.of(
                module(
                        "matchmaking",
                        extension(
                                "matchmaking-paper",
                                PAPER,
                                List.of(),
                                variant("paper", "*", "extensions/paper/paper.jar"))),
                module(
                        "proxy",
                        extension(
                                "proxy-sync",
                                RuntimeTarget.parse("proxy/velocity"),
                                List.of(),
                                variant("velocity", "*", "extensions/velocity/proxy.jar")))));

        assertEquals(2, registry.listExtensions().size());
        assertEquals(
                List.of("matchmaking-paper"),
                registry.listExtensions(PAPER).stream()
                        .map(e -> e.extension().id())
                        .toList());
    }

    @Test
    @DisplayName("lists compatible variants for a runtime target")
    void listsCompatibleVariantsForRuntimeTarget() {
        ExtensionRegistry registry = new ExtensionRegistry(List.of(
                module(
                        "matchmaking",
                        extension(
                                "matchmaking-paper",
                                PAPER,
                                List.of(),
                                variant("paper-1-20", "[1.20,1.21)", "extensions/paper/paper-1.20.jar"))),
                module(
                        "queue",
                        extension(
                                "queue-paper",
                                PAPER,
                                List.of(),
                                variant("paper-1-21", "[1.21,1.22)", "extensions/paper/paper-1.21.jar"))),
                module(
                        "proxy",
                        extension(
                                "proxy-sync",
                                RuntimeTarget.parse("proxy/velocity"),
                                List.of(),
                                variant("velocity", "*", "extensions/velocity/proxy.jar")))));

        List<ExtensionRegistry.ResolvedVariant> resolved = registry.listCompatibleVariants(PAPER, "1.20.4");

        assertEquals(
                List.of("matchmaking-paper"),
                resolved.stream()
                        .map(ExtensionRegistry.ResolvedVariant::extensionId)
                        .toList());
        assertEquals("paper-1-20", resolved.getFirst().variant().id());
    }

    @Test
    @DisplayName("selects the most specific compatible variant")
    void selectsMostSpecificVariant() {
        ExtensionRegistry registry = new ExtensionRegistry(List.of(module(
                "matchmaking",
                extension(
                        "matchmaking-paper",
                        PAPER,
                        List.of(),
                        variant("universal", "*", "extensions/paper/universal.jar"),
                        variant("paper-1-20", "[1.20,1.21)", "extensions/paper/paper-1.20.jar"),
                        variant("paper-1-20-4", "[1.20.4,1.20.5)", "extensions/paper/paper-1.20.4.jar")))));

        ExtensionRegistry.ResolvedVariant resolved = registry.resolveVariant("matchmaking-paper", PAPER, "1.20.4");

        assertEquals("paper-1-20-4", resolved.variant().id());
    }

    @Test
    @DisplayName("fails when no compatible variant exists")
    void noCompatibleVariant() {
        ExtensionRegistry registry = new ExtensionRegistry(List.of(module(
                "matchmaking",
                extension(
                        "matchmaking-paper",
                        PAPER,
                        List.of(),
                        variant("paper-1-20", "[1.20,1.21)", "extensions/paper/paper-1.20.jar")))));

        ExtensionRegistryException ex = assertThrows(
                ExtensionRegistryException.class, () -> registry.resolveVariant("matchmaking-paper", PAPER, "1.21"));
        assertTrue(ex.getMessage().contains("no compatible variant"));
    }

    @Test
    @DisplayName("fails on equal-specificity ambiguity")
    void ambiguity() {
        ExtensionRegistry registry = new ExtensionRegistry(List.of(module(
                "matchmaking",
                extension(
                        "matchmaking-paper",
                        PAPER,
                        List.of(),
                        variant("interval", "[1.20,1.21)", "extensions/paper/paper-interval.jar"),
                        variant("comparators", ">=1.20 <1.21", "extensions/paper/paper-comparators.jar")))));

        ExtensionRegistryException ex = assertThrows(
                ExtensionRegistryException.class, () -> registry.resolveVariant("matchmaking-paper", PAPER, "1.20.4"));
        assertTrue(ex.getMessage().contains("ambiguous"));
    }

    @Test
    @DisplayName("resolveVariants detects extension conflicts")
    void detectsExtensionConflicts() {
        ExtensionRegistry registry = new ExtensionRegistry(List.of(
                module(
                        "alpha",
                        extension(
                                "alpha-paper",
                                PAPER,
                                List.of("beta-paper"),
                                variant("alpha", "*", "extensions/paper/alpha.jar"))),
                module(
                        "beta",
                        extension("beta-paper", PAPER, List.of(), variant("beta", "*", "extensions/paper/beta.jar")))));

        ExtensionRegistryException ex = assertThrows(
                ExtensionRegistryException.class,
                () -> registry.resolveVariants(List.of("alpha-paper", "beta-paper"), PAPER, "1.20.1"));
        assertTrue(ex.getMessage().contains("conflicts"));
    }

    @Test
    @DisplayName("resolveVariants detects install path collisions")
    void detectsInstallPathCollisions() {
        ExtensionRegistry registry = new ExtensionRegistry(List.of(
                module(
                        "alpha",
                        extension(
                                "alpha-paper", PAPER, List.of(), variant("alpha", "*", "extensions/paper/shared.jar"))),
                module(
                        "beta",
                        extension(
                                "beta-paper", PAPER, List.of(), variant("beta", "*", "extensions/paper/shared.jar")))));

        ExtensionRegistryException ex = assertThrows(
                ExtensionRegistryException.class,
                () -> registry.resolveVariants(List.of("alpha-paper", "beta-paper"), PAPER, "1.20.1"));
        assertTrue(ex.getMessage().contains("install"));
    }

    private static PlatformModuleManifest module(String id, WorkloadExtensionManifest extension) {
        return new PlatformModuleManifest(
                1,
                id,
                "1.0.0",
                new PlatformModuleManifest.Backend("com.example." + id),
                null,
                null,
                null,
                List.of(extension));
    }

    private static WorkloadExtensionManifest extension(
            String id, RuntimeTarget target, List<String> conflicts, WorkloadExtensionVariant... variants) {
        return new WorkloadExtensionManifest(
                id, target, ActivationPolicy.EXPLICIT_GROUP_ATTACH, conflicts, List.of(variants));
    }

    private static WorkloadExtensionVariant variant(String id, String range, String artifact) {
        return new WorkloadExtensionVariant(
                id, range, 1, artifact, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "plugins/");
    }
}
