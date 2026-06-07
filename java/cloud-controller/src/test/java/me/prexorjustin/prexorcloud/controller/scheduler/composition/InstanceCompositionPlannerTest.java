package me.prexorjustin.prexorcloud.controller.scheduler.composition;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfig;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfigLoader;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.crash.CrashRecord;
import me.prexorjustin.prexorcloud.controller.crash.CrashTrendPoint;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleRuntimeFactory;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleStore;
import me.prexorjustin.prexorcloud.controller.state.HealingActionIntent;
import me.prexorjustin.prexorcloud.controller.state.NodeDrainIntent;
import me.prexorjustin.prexorcloud.controller.state.StartRetryIntent;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.state.TemplateVariable;
import me.prexorjustin.prexorcloud.controller.state.TemplateVersion;
import me.prexorjustin.prexorcloud.controller.state.TransferIntent;
import me.prexorjustin.prexorcloud.controller.template.TemplateConfig;
import me.prexorjustin.prexorcloud.controller.template.TemplateManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("InstanceCompositionPlanner")
class InstanceCompositionPlannerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("resolves template chain, recommended runtime version, and default-enabled extensions")
    void resolvesCompositionPlan() throws Exception {
        Path templatesRoot = tempDir.resolve("templates");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-paper");
        createTemplate(templatesRoot, templateManager, "lobby");
        createTemplate(templatesRoot, templateManager, "motd");

        PlatformModuleManager platformModuleManager = new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve("platform-store")),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(),
                        new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                        () -> {}));
        platformModuleManager.install(createModuleJar(tempDir.resolve("motd-module.jar"), """
                manifestVersion: 1
                id: motd-module
                version: 1.0.0
                backend:
                  entrypoint: example.MotdModule
                extensions:
                  - id: motd-paper
                    target: server/paper
                    activation: default-enabled
                    variants:
                      - id: paper-1-21
                        mcVersionRange: "[1.21,1.22)"
                        runtimeApiVersion: 1
                        artifact: extensions/motd-paper.jar
                        sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
                        installPath: plugins/
                """));

        InstanceCompositionPlanner planner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                        "PAPER",
                        "SERVER",
                        "PAPER",
                        "1.21.4",
                        "https://example.invalid/paper.jar",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        true))),
                platformModuleManager);

        GroupConfig group = new GroupConfig(
                "lobby",
                null,
                "PAPER",
                "",
                "server.jar",
                List.of("motd"),
                "DYNAMIC",
                1,
                3,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of("-XX:+UseG1GC"),
                Map.of("EXAMPLE_FLAG", "1"),
                List.of("Lobby MOTD"),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());

        InstanceCompositionPlan plan = planner.plan(group, "lobby-1", "node-a", 30001, "http://controller:8080");

        assertEquals(
                List.of("base", "base-paper", "lobby", "motd"),
                plan.templates().stream()
                        .map(InstanceCompositionPlan.ResolvedTemplate::name)
                        .toList());
        assertEquals("1.21.4", plan.runtime().platformVersion());
        assertEquals("server/paper", plan.runtime().runtimeTarget());
        assertEquals("https://example.invalid/paper.jar", plan.runtime().downloadUrl());
        assertEquals(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                plan.runtime().sha256());
        assertEquals("http://controller:8080", plan.env().get("CLOUD_CONTROLLER_URL"));
        assertEquals(0.0, plan.isolation().cpuReservation());
        assertEquals(0, plan.isolation().diskReservationMb());
        assertEquals(1, plan.extensions().size());
        assertEquals("motd-paper", plan.extensions().getFirst().extensionId());
        assertEquals("paper-1-21", plan.extensions().getFirst().variantId());
        assertEquals(
                "http://controller:8080/api/v1/modules/platform/motd-module/artifacts/extensions/motd-paper.jar",
                plan.extensions().getFirst().downloadUrl());
        assertEquals(
                List.of(
                        new InstanceCompositionPlan.ResolvedConfigPatch("server.properties", "max-players", "100"),
                        new InstanceCompositionPlan.ResolvedConfigPatch("server.properties", "motd", "Lobby MOTD"),
                        new InstanceCompositionPlan.ResolvedConfigPatch("server.properties", "server-port", "30001")),
                plan.configPatches());
    }

    @Test
    @DisplayName("resolves explicit extension attachments, disabled defaults, and merged config patches")
    void resolvesGroupControlledExtensionsAndConfigPatches() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-explicit");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-paper");
        createTemplate(templatesRoot, templateManager, "proxy");

        PlatformModuleManager platformModuleManager = new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve("platform-store-explicit")),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(),
                        new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                        () -> {}));
        platformModuleManager.install(createModuleJar(tempDir.resolve("proxy-module.jar"), """
                manifestVersion: 1
                id: proxy-module
                version: 1.0.0
                backend:
                  entrypoint: example.ProxyModule
                extensions:
                  - id: proxy-default
                    target: proxy/velocity
                    activation: default-enabled
                    variants:
                      - id: velocity-default
                        mcVersionRange: "*"
                        runtimeApiVersion: 1
                        artifact: extensions/proxy-default.jar
                        sha256: "1111111111111111111111111111111111111111111111111111111111111111"
                        installPath: plugins/
                  - id: proxy-explicit
                    target: proxy/velocity
                    activation: explicit-group-attach
                    variants:
                      - id: velocity-explicit
                        mcVersionRange: "*"
                        runtimeApiVersion: 1
                        artifact: extensions/proxy-explicit.jar
                        sha256: "2222222222222222222222222222222222222222222222222222222222222222"
                        installPath: plugins/
                """));

        InstanceCompositionPlanner planner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                        "VELOCITY",
                        "PROXY",
                        "VELOCITY",
                        "3.3.0",
                        "https://example.invalid/velocity.jar",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        true))),
                platformModuleManager);

        GroupConfig group = new GroupConfig(
                "proxy",
                null,
                "VELOCITY",
                "",
                "proxy.jar",
                List.of(),
                "DYNAMIC",
                1,
                2,
                200,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of("-XX:+UseG1GC"),
                Map.of(),
                List.of("<green>Proxy</green>"),
                "STATIC",
                30,
                List.of("proxy-explicit"),
                List.of(),
                List.of("proxy-default"),
                Map.of("velocity.toml", Map.of("show-max-players", "250")));

        InstanceCompositionPlan plan = planner.plan(group, "proxy-1", "node-a", 30100, "http://controller:8080");

        assertEquals(
                List.of("proxy-explicit"),
                plan.extensions().stream()
                        .map(InstanceCompositionPlan.ResolvedExtension::extensionId)
                        .toList());
        assertEquals(
                List.of(
                        new InstanceCompositionPlan.ResolvedConfigPatch("velocity.toml", "bind", "0.0.0.0:30100"),
                        new InstanceCompositionPlan.ResolvedConfigPatch(
                                "velocity.toml", "motd", "<green>Proxy</green>"),
                        new InstanceCompositionPlan.ResolvedConfigPatch("velocity.toml", "show-max-players", "250")),
                plan.configPatches());
    }

    @Test
    @DisplayName("group runtime version changes switch the resolved extension variant")
    void groupRuntimeVersionChangeSwitchesResolvedVariant() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-variant-switch");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-paper");
        createTemplate(templatesRoot, templateManager, "lobby");

        PlatformModuleManager platformModuleManager = new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve("platform-store-variant-switch")),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(),
                        new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                        () -> {}));
        platformModuleManager.install(createModuleJar(tempDir.resolve("matchmaking-module.jar"), """
                manifestVersion: 1
                id: matchmaking-module
                version: 1.0.0
                backend:
                  entrypoint: example.MatchmakingModule
                extensions:
                  - id: matchmaking-paper
                    target: server/paper
                    activation: explicit-group-attach
                    variants:
                      - id: paper-1-20
                        mcVersionRange: "[1.20.0,1.21.0)"
                        runtimeApiVersion: 1
                        artifact: extensions/matchmaking-1.20.jar
                        sha256: "1111111111111111111111111111111111111111111111111111111111111111"
                        installPath: plugins/
                      - id: paper-1-20-4
                        mcVersionRange: "[1.20.4,1.20.5)"
                        runtimeApiVersion: 1
                        artifact: extensions/matchmaking-1.20.4.jar
                        sha256: "2222222222222222222222222222222222222222222222222222222222222222"
                        installPath: plugins/
                """));

        InstanceCompositionPlanner planner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(List.of(
                        new CatalogConfigLoader.CatalogEntry(
                                "PAPER",
                                "SERVER",
                                "PAPER",
                                "1.20.1",
                                "https://example.invalid/paper-1.20.1.jar",
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1",
                                false),
                        new CatalogConfigLoader.CatalogEntry(
                                "PAPER",
                                "SERVER",
                                "PAPER",
                                "1.20.4",
                                "https://example.invalid/paper-1.20.4.jar",
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa4",
                                true))),
                platformModuleManager);

        GroupConfig paper1201 = new GroupConfig(
                "lobby-1201",
                null,
                "PAPER",
                "1.20.1",
                "server.jar",
                List.of("Lobby"),
                "DYNAMIC",
                1,
                3,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of(),
                Map.of(),
                List.of("Lobby"),
                "STATIC",
                30,
                List.of("matchmaking-paper"),
                List.of(),
                List.of(),
                Map.of());
        GroupConfig paper1204 = new GroupConfig(
                "lobby-1204",
                null,
                "PAPER",
                "1.20.4",
                "server.jar",
                List.of("Lobby"),
                "DYNAMIC",
                1,
                3,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of(),
                Map.of(),
                List.of("Lobby"),
                "STATIC",
                30,
                List.of("matchmaking-paper"),
                List.of(),
                List.of(),
                Map.of());

        InstanceCompositionPlan plan1201 =
                planner.plan(paper1201, "lobby-1201-1", "node-a", 30001, "http://controller:8080");
        InstanceCompositionPlan plan1204 =
                planner.plan(paper1204, "lobby-1204-1", "node-a", 30002, "http://controller:8080");

        assertEquals("1.20.1", plan1201.runtime().platformVersion());
        assertEquals(
                "https://example.invalid/paper-1.20.1.jar", plan1201.runtime().downloadUrl());
        assertEquals("paper-1-20", plan1201.extensions().getFirst().variantId());
        assertEquals(
                "http://controller:8080/api/v1/modules/platform/matchmaking-module/artifacts/extensions/matchmaking-1.20.jar",
                plan1201.extensions().getFirst().downloadUrl());

        assertEquals("1.20.4", plan1204.runtime().platformVersion());
        assertEquals(
                "https://example.invalid/paper-1.20.4.jar", plan1204.runtime().downloadUrl());
        assertEquals("paper-1-20-4", plan1204.extensions().getFirst().variantId());
        assertEquals(
                "http://controller:8080/api/v1/modules/platform/matchmaking-module/artifacts/extensions/matchmaking-1.20.4.jar",
                plan1204.extensions().getFirst().downloadUrl());
    }

    @Test
    @DisplayName("applies group-level module policy before extension activation policy")
    void appliesGroupModulePolicy() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-module-policy");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-paper");
        createTemplate(templatesRoot, templateManager, "lobby");

        PlatformModuleManager platformModuleManager = new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve("platform-store-module-policy")),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(),
                        new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                        () -> {}));
        platformModuleManager.install(createModuleJar(tempDir.resolve("motd-module.jar"), """
                manifestVersion: 1
                id: motd-module
                version: 1.0.0
                backend:
                  entrypoint: example.MotdModule
                extensions:
                  - id: motd-default
                    target: server/paper
                    activation: default-enabled
                    variants:
                      - id: motd-paper
                        mcVersionRange: "[1.21,1.22)"
                        runtimeApiVersion: 1
                        artifact: extensions/motd-paper.jar
                        sha256: "1111111111111111111111111111111111111111111111111111111111111111"
                        installPath: plugins/
                """));
        platformModuleManager.install(createModuleJar(tempDir.resolve("chat-module.jar"), """
                manifestVersion: 1
                id: chat-module
                version: 1.0.0
                backend:
                  entrypoint: example.ChatModule
                extensions:
                  - id: chat-explicit
                    target: server/paper
                    activation: explicit-group-attach
                    variants:
                      - id: chat-paper
                        mcVersionRange: "[1.21,1.22)"
                        runtimeApiVersion: 1
                        artifact: extensions/chat-paper.jar
                        sha256: "2222222222222222222222222222222222222222222222222222222222222222"
                        installPath: plugins/
                """));
        platformModuleManager.install(createModuleJar(tempDir.resolve("debug-module.jar"), """
                manifestVersion: 1
                id: debug-module
                version: 1.0.0
                backend:
                  entrypoint: example.DebugModule
                extensions:
                  - id: debug-default
                    target: server/paper
                    activation: default-enabled
                    variants:
                      - id: debug-paper
                        mcVersionRange: "[1.21,1.22)"
                        runtimeApiVersion: 1
                        artifact: extensions/debug-paper.jar
                        sha256: "3333333333333333333333333333333333333333333333333333333333333333"
                        installPath: plugins/
                """));

        InstanceCompositionPlanner planner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                        "PAPER",
                        "SERVER",
                        "PAPER",
                        "1.21.4",
                        "https://example.invalid/paper.jar",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        true))),
                platformModuleManager);

        GroupConfig group = new GroupConfig(
                "lobby",
                null,
                "PAPER",
                "",
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                3,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of(),
                Map.of(),
                List.of("Lobby"),
                "STATIC",
                30,
                List.of("chat-module"),
                List.of("motd-module"),
                List.of("debug-module"),
                List.of(),
                List.of(),
                List.of(),
                Map.of());

        InstanceCompositionPlan plan = planner.plan(group, "lobby-1", "node-a", 30001, "http://controller:8080");

        assertEquals(
                List.of("chat-explicit", "motd-default"),
                plan.extensions().stream()
                        .map(InstanceCompositionPlan.ResolvedExtension::extensionId)
                        .toList());
    }

    @Test
    @DisplayName("rejects contradictory group extension policies")
    void rejectsContradictoryGroupExtensionPolicies() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-policy");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-velocity");
        createTemplate(templatesRoot, templateManager, "proxy");

        PlatformModuleManager platformModuleManager = new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve("platform-store-policy")),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(),
                        new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                        () -> {}));
        platformModuleManager.install(createModuleJar(tempDir.resolve("policy-module.jar"), """
                manifestVersion: 1
                id: policy-module
                version: 1.0.0
                backend:
                  entrypoint: example.PolicyModule
                extensions:
                  - id: proxy-explicit
                    target: proxy/velocity
                    activation: explicit-group-attach
                    variants:
                      - id: velocity-explicit
                        mcVersionRange: "*"
                        runtimeApiVersion: 1
                        artifact: extensions/proxy-explicit.jar
                        sha256: "7777777777777777777777777777777777777777777777777777777777777777"
                        installPath: plugins/
                """));

        InstanceCompositionPlanner planner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                        "VELOCITY",
                        "PROXY",
                        "VELOCITY",
                        "3.3.0",
                        "https://example.invalid/velocity.jar",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        true))),
                platformModuleManager);

        GroupConfig group = new GroupConfig(
                "proxy",
                null,
                "VELOCITY",
                "",
                "proxy.jar",
                List.of(),
                "DYNAMIC",
                1,
                2,
                200,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of("-XX:+UseG1GC"),
                Map.of(),
                List.of("<green>Proxy</green>"),
                "STATIC",
                30,
                List.of("proxy-explicit"),
                List.of(),
                List.of("proxy-explicit"),
                Map.of());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> planner.plan(group, "proxy-1", "node-a", 30100, "http://controller:8080"));
        assertTrue(exception.getMessage().contains("both attaches and disables"));
    }

    @Test
    @DisplayName("resolves bungeecord config patches for proxy runtimes")
    void resolvesBungeecordConfigPatches() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-bungee");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-bungeecord");
        createTemplate(templatesRoot, templateManager, "proxy-bungee");

        InstanceCompositionPlanner planner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                        "BUNGEECORD",
                        "PROXY",
                        "BUNGEECORD",
                        "1.20",
                        "https://example.invalid/bungeecord.jar",
                        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                        true))),
                new PlatformModuleManager(
                        new PlatformModuleStore(tempDir.resolve("platform-store-bungee")),
                        storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(),
                                new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                                () -> {})));

        GroupConfig group = new GroupConfig(
                "proxy-bungee",
                null,
                "BUNGEECORD",
                "",
                "proxy.jar",
                List.of(),
                "DYNAMIC",
                1,
                2,
                150,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of("-XX:+UseG1GC"),
                Map.of(),
                List.of("<gold>Bungee</gold>"),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());

        InstanceCompositionPlan plan = planner.plan(group, "proxy-bungee-1", "node-a", 30100, "http://controller:8080");

        assertEquals("BUNGEECORD", plan.runtime().configFormat());
        assertEquals(
                List.of(
                        new InstanceCompositionPlan.ResolvedConfigPatch("config.yml", "host", "0.0.0.0:30100"),
                        new InstanceCompositionPlan.ResolvedConfigPatch("config.yml", "max_players", "150"),
                        new InstanceCompositionPlan.ResolvedConfigPatch("config.yml", "motd", "<gold>Bungee</gold>")),
                plan.configPatches());
    }

    @Test
    @DisplayName("selects the highest runtime version deterministically when no catalog entry is recommended")
    void selectsHighestCatalogVersionDeterministically() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-catalog-order");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-paper");
        createTemplate(templatesRoot, templateManager, "lobby");

        List<CatalogConfigLoader.CatalogEntry> descendingEntries = List.of(
                new CatalogConfigLoader.CatalogEntry(
                        "PAPER",
                        "SERVER",
                        "PAPER",
                        "1.20.6",
                        "https://example.invalid/paper-1.20.6.jar",
                        "1111111111111111111111111111111111111111111111111111111111111111",
                        false),
                new CatalogConfigLoader.CatalogEntry(
                        "PAPER",
                        "SERVER",
                        "PAPER",
                        "1.21.4",
                        "https://example.invalid/paper-1.21.4.jar",
                        "2222222222222222222222222222222222222222222222222222222222222222",
                        false),
                new CatalogConfigLoader.CatalogEntry(
                        "PAPER",
                        "SERVER",
                        "PAPER",
                        "1.21.2",
                        "https://example.invalid/paper-1.21.2.jar",
                        "3333333333333333333333333333333333333333333333333333333333333333",
                        false));
        List<CatalogConfigLoader.CatalogEntry> ascendingEntries =
                List.of(descendingEntries.get(2), descendingEntries.get(0), descendingEntries.get(1));

        InstanceCompositionPlanner firstPlanner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(descendingEntries),
                new PlatformModuleManager(
                        new PlatformModuleStore(tempDir.resolve("platform-store-catalog-a")),
                        storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(),
                                new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                                () -> {})));
        InstanceCompositionPlanner secondPlanner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(ascendingEntries),
                new PlatformModuleManager(
                        new PlatformModuleStore(tempDir.resolve("platform-store-catalog-b")),
                        storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(),
                                new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                                () -> {})));

        GroupConfig group = new GroupConfig(
                "lobby",
                null,
                "PAPER",
                "",
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                3,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of("-XX:+UseG1GC"),
                Map.of(),
                List.of("Lobby"),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());

        InstanceCompositionPlan first = firstPlanner.plan(group, "lobby-1", "node-a", 30001, "http://controller:8080");
        InstanceCompositionPlan second =
                secondPlanner.plan(group, "lobby-1", "node-a", 30001, "http://controller:8080");

        assertEquals("1.21.4", first.runtime().platformVersion());
        assertEquals("https://example.invalid/paper-1.21.4.jar", first.runtime().downloadUrl());
        assertEquals(first.runtime(), second.runtime());
        assertEquals(first.planHash(), second.planHash());
    }

    @Test
    @DisplayName("rejects enabled extension allowlists that do not match the runtime target")
    void rejectsUnknownEnabledExtensions() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-enabled-policy");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-velocity");
        createTemplate(templatesRoot, templateManager, "proxy");

        PlatformModuleManager platformModuleManager = new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve("platform-store-enabled-policy")),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(),
                        new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                        () -> {}));
        platformModuleManager.install(createModuleJar(tempDir.resolve("enabled-policy-module.jar"), """
                manifestVersion: 1
                id: enabled-policy-module
                version: 1.0.0
                backend:
                  entrypoint: example.EnabledPolicyModule
                extensions:
                  - id: proxy-default
                    target: proxy/velocity
                    activation: default-enabled
                    variants:
                      - id: velocity-default
                        mcVersionRange: "*"
                        runtimeApiVersion: 1
                        artifact: extensions/proxy-default.jar
                        sha256: "6666666666666666666666666666666666666666666666666666666666666666"
                        installPath: plugins/
                """));

        InstanceCompositionPlanner planner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                        "VELOCITY",
                        "PROXY",
                        "VELOCITY",
                        "3.3.0",
                        "https://example.invalid/velocity.jar",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        true))),
                platformModuleManager);

        GroupConfig group = new GroupConfig(
                "proxy",
                null,
                "VELOCITY",
                "",
                "proxy.jar",
                List.of(),
                "DYNAMIC",
                1,
                2,
                200,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of("-XX:+UseG1GC"),
                Map.of(),
                List.of("<green>Proxy</green>"),
                "STATIC",
                30,
                List.of(),
                List.of("paper-only-extension"),
                List.of(),
                Map.of());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> planner.plan(group, "proxy-1", "node-a", 30100, "http://controller:8080"));
        assertTrue(exception.getMessage().contains("enables unknown or incompatible"));
    }

    @Test
    @DisplayName("resolves enabled default extensions deterministically across module install order")
    void resolvesExtensionsDeterministicallyAcrossModuleInstallOrder() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-extension-order");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-paper");
        createTemplate(templatesRoot, templateManager, "lobby");

        PlatformModuleManager firstModuleManager = new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve("platform-store-order-a")),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(),
                        new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                        () -> {}));
        PlatformModuleManager secondModuleManager = new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve("platform-store-order-b")),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(),
                        new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                        () -> {}));

        Path alphaModule = createModuleJar(tempDir.resolve("alpha-module.jar"), """
                manifestVersion: 1
                id: alpha-module
                version: 1.0.0
                backend:
                  entrypoint: example.AlphaModule
                extensions:
                  - id: zeta-extension
                    target: server/paper
                    activation: default-enabled
                    variants:
                      - id: paper-zeta
                        mcVersionRange: "*"
                        runtimeApiVersion: 1
                        artifact: extensions/zeta.jar
                        sha256: "4444444444444444444444444444444444444444444444444444444444444444"
                        installPath: plugins/
                """);
        Path betaModule = createModuleJar(tempDir.resolve("beta-module.jar"), """
                manifestVersion: 1
                id: beta-module
                version: 1.0.0
                backend:
                  entrypoint: example.BetaModule
                extensions:
                  - id: alpha-extension
                    target: server/paper
                    activation: default-enabled
                    variants:
                      - id: paper-alpha
                        mcVersionRange: "*"
                        runtimeApiVersion: 1
                        artifact: extensions/alpha.jar
                        sha256: "5555555555555555555555555555555555555555555555555555555555555555"
                        installPath: plugins/
                """);
        firstModuleManager.install(alphaModule);
        firstModuleManager.install(betaModule);
        secondModuleManager.install(betaModule);
        secondModuleManager.install(alphaModule);

        InstanceCompositionPlanner firstPlanner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                        "PAPER",
                        "SERVER",
                        "PAPER",
                        "1.21.4",
                        "https://example.invalid/paper.jar",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        true))),
                firstModuleManager);
        InstanceCompositionPlanner secondPlanner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                        "PAPER",
                        "SERVER",
                        "PAPER",
                        "1.21.4",
                        "https://example.invalid/paper.jar",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        true))),
                secondModuleManager);

        GroupConfig group = new GroupConfig(
                "lobby",
                null,
                "PAPER",
                "",
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                3,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of("-XX:+UseG1GC"),
                Map.of(),
                List.of("Lobby"),
                "STATIC",
                30,
                List.of(),
                List.of("alpha-extension"),
                List.of(),
                Map.of());

        InstanceCompositionPlan first = firstPlanner.plan(group, "lobby-1", "node-a", 30001, "http://controller:8080");
        InstanceCompositionPlan second =
                secondPlanner.plan(group, "lobby-1", "node-a", 30001, "http://controller:8080");

        assertEquals(
                List.of("alpha-extension"),
                first.extensions().stream()
                        .map(InstanceCompositionPlan.ResolvedExtension::extensionId)
                        .toList());
        assertEquals(first.extensions(), second.extensions());
        assertEquals(first.planHash(), second.planHash());
    }

    @Test
    @DisplayName("produces a stable plan hash for identical inputs")
    void producesStablePlanHash() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-stable");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-paper");
        createTemplate(templatesRoot, templateManager, "lobby");

        InstanceCompositionPlanner planner = new InstanceCompositionPlanner(
                templateManager,
                new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                        "PAPER",
                        "SERVER",
                        "PAPER",
                        "1.21.4",
                        "https://example.invalid/paper.jar",
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        true))),
                new PlatformModuleManager(
                        new PlatformModuleStore(tempDir.resolve("platform-store-stable")),
                        storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                                storedModule.manifest(),
                                new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                                () -> {})));

        GroupConfig group = new GroupConfig(
                "lobby",
                null,
                "PAPER",
                "",
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                3,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of("-XX:+UseG1GC"),
                Map.of("EXAMPLE_FLAG", "1"),
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());

        InstanceCompositionPlan first = planner.plan(group, "lobby-1", "node-a", 30001, "http://controller:8080");
        InstanceCompositionPlan second = planner.plan(group, "lobby-1", "node-a", 30001, "http://controller:8080");

        assertEquals(first.planHash(), second.planHash());
        assertEquals(first.templates(), second.templates());
        assertEquals(first.runtime(), second.runtime());
        assertEquals(first.extensions(), second.extensions());
        assertEquals(first.isolation(), second.isolation());
    }

    @Test
    @DisplayName("resolves geyser remote.* patches from a live proxy endpoint")
    void resolvesGeyserRemotePatches() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-geyser");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-geyser");

        InstanceCompositionPlanner planner = new InstanceCompositionPlanner(
                templateManager,
                geyserCatalog(),
                noopModuleManager("platform-store-geyser"),
                null,
                proxyGroup -> "proxy-velocity".equals(proxyGroup)
                        ? Optional.of(new BedrockRemoteResolver.Endpoint("10.0.0.7", 30100))
                        : Optional.empty());

        GroupConfig group = geyserGroup("bedrock", "proxy-velocity");
        InstanceCompositionPlan plan = planner.plan(group, "bedrock-1", "node-a", 19132, "http://controller:8080");

        assertEquals("GEYSER", plan.runtime().configFormat());
        assertEquals(
                List.of(
                        new InstanceCompositionPlan.ResolvedConfigPatch("config.yml", "remote.address", "10.0.0.7"),
                        new InstanceCompositionPlan.ResolvedConfigPatch("config.yml", "remote.port", "30100")),
                plan.configPatches());
    }

    @Test
    @DisplayName("omits geyser remote.* patches when no proxy instance is running (cold start)")
    void omitsGeyserRemotePatchesOnColdStart() throws Exception {
        Path templatesRoot = tempDir.resolve("templates-geyser-cold");
        TemplateManager templateManager = new TemplateManager(templatesRoot, new NoopStateStore(), new EventBus());
        createTemplate(templatesRoot, templateManager, "base");
        createTemplate(templatesRoot, templateManager, "base-geyser");

        InstanceCompositionPlanner planner = new InstanceCompositionPlanner(
                templateManager,
                geyserCatalog(),
                noopModuleManager("platform-store-geyser-cold"),
                null,
                proxyGroup -> Optional.empty());

        GroupConfig group = geyserGroup("bedrock", "proxy-velocity");
        InstanceCompositionPlan plan = planner.plan(group, "bedrock-1", "node-a", 19132, "http://controller:8080");

        assertEquals(List.of(), plan.configPatches());
    }

    private StaticCatalogStore geyserCatalog() {
        return new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                "GEYSER",
                "PROXY",
                "GEYSER",
                "2.6.2",
                "https://example.invalid/geyser.jar",
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                true)));
    }

    private PlatformModuleManager noopModuleManager(String storeDir) {
        return new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve(storeDir)),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(),
                        new me.prexorjustin.prexorcloud.api.module.platform.PlatformModule() {},
                        () -> {}));
    }

    private static GroupConfig geyserGroup(String name, String bedrockProxyGroup) {
        return new GroupConfig(
                name,
                null,
                "GEYSER",
                "",
                "Geyser.jar",
                List.of(),
                "DYNAMIC",
                1,
                2,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                0.0,
                0L,
                List.of(),
                Map.of(),
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                bedrockProxyGroup);
    }

    private static void createTemplate(Path templatesRoot, TemplateManager templateManager, String name)
            throws IOException {
        Path filesDir = templatesRoot.resolve(name).resolve("files");
        Files.createDirectories(filesDir);
        Files.writeString(filesDir.resolve("marker.txt"), name, StandardCharsets.UTF_8);
        templateManager.scanAndHash();
    }

    private static Path createModuleJar(Path jarPath, String manifest) {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath))) {
            out.putNextEntry(new JarEntry("META-INF/prexor/module.yaml"));
            out.write(manifest.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            return jarPath;
        } catch (IOException e) {
            throw new IllegalStateException("failed to create module jar", e);
        }
    }

    private record StaticCatalogStore(List<CatalogConfigLoader.CatalogEntry> entries) implements CatalogStore {
        @Override
        public List<CatalogConfigLoader.CatalogEntry> getAll() {
            return entries;
        }

        @Override
        public List<CatalogConfigLoader.CatalogEntry> getByPlatform(String platform) {
            return entries.stream()
                    .filter(entry -> entry.platform().equals(platform))
                    .toList();
        }

        @Override
        public Optional<CatalogConfig.PlatformCatalog> getPlatform(String platform) {
            return Optional.empty();
        }

        @Override
        public boolean addEntry(
                String platform,
                String category,
                String configFormat,
                String version,
                String downloadUrl,
                String sha256) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateEntry(
                String platform, String oldVersion, String newVersion, String downloadUrl, String sha256) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeEntry(String platform, String version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setRecommended(String platform, String version) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopStateStore implements StateStore {
        @Override
        public void initialize() {}

        @Override
        public void close() {}

        @Override
        public void runInTransaction(Runnable action) {
            action.run();
        }

        @Override
        public void saveTemplate(TemplateConfig config) {}

        @Override
        public Optional<TemplateConfig> getTemplate(String name) {
            return Optional.empty();
        }

        @Override
        public List<TemplateConfig> getAllTemplates() {
            return List.of();
        }

        @Override
        public void deleteTemplate(String name) {}

        @Override
        public void recordTemplateVersion(String templateName, String hash, long sizeBytes) {}

        @Override
        public List<TemplateVersion> getTemplateVersions(String templateName) {
            return List.of();
        }

        @Override
        public Optional<TemplateVersion> getLatestTemplateVersion(String templateName) {
            return Optional.empty();
        }

        @Override
        public void deleteTemplateVersion(String templateName, String hash) {}

        @Override
        public List<TemplateVariable> getTemplateVariables(String templateName) {
            return List.of();
        }

        @Override
        public void saveTemplateVariables(String templateName, List<TemplateVariable> variables) {}

        @Override
        public DeploymentRecord createDeployment(DeploymentRecord record) {
            return record;
        }

        @Override
        public Optional<DeploymentRecord> getDeployment(String groupName, int revision) {
            return Optional.empty();
        }

        @Override
        public List<DeploymentRecord> getDeployments(String groupName, int limit, int offset) {
            return List.of();
        }

        @Override
        public int countDeployments(String groupName) {
            return 0;
        }

        @Override
        public void updateDeploymentState(int id, String state) {}

        @Override
        public void updateDeploymentProgress(int id, int updatedInstances) {}

        @Override
        public List<DeploymentRecord> getDeploymentsByState(String state, int limit) {
            return List.of();
        }

        @Override
        public void saveCrash(CrashRecord record) {}

        @Override
        public Optional<CrashRecord> getCrash(String id) {
            return Optional.empty();
        }

        @Override
        public List<CrashRecord> getCrashes(String group, String nodeId, int limit, int offset) {
            return List.of();
        }

        @Override
        public int countCrashes(String group, String nodeId) {
            return 0;
        }

        @Override
        public List<CrashTrendPoint> getCrashTrend(String group, String nodeId, java.time.Instant since) {
            return List.of();
        }

        @Override
        public void saveShareRecord(me.prexorjustin.prexorcloud.controller.share.ShareRecord record) {}

        @Override
        public Optional<me.prexorjustin.prexorcloud.controller.share.ShareRecord> getShareRecord(String id) {
            return Optional.empty();
        }

        @Override
        public List<me.prexorjustin.prexorcloud.controller.share.ShareRecord> getShareRecords(
                me.prexorjustin.prexorcloud.controller.share.ShareKind kind,
                boolean activeOnly,
                int limit,
                int offset) {
            return List.of();
        }

        @Override
        public int countShareRecords(me.prexorjustin.prexorcloud.controller.share.ShareKind kind, boolean activeOnly) {
            return 0;
        }

        @Override
        public void markShareRevoked(String id, java.time.Instant when) {}

        @Override
        public void audit(
                String username,
                String action,
                String resourceType,
                String resourceId,
                String details,
                String ipAddress) {}

        @Override
        public void audit(
                String username,
                String action,
                String resourceType,
                String resourceId,
                String details,
                String beforeJson,
                String afterJson,
                String ipAddress) {}

        @Override
        public List<AuditEntry> getAuditLog(int limit, int offset) {
            return List.of();
        }

        @Override
        public AuditLogPage getAuditLogSeek(String cursor, int limit) {
            return new AuditLogPage(List.of(), null);
        }

        @Override
        public int countAuditLog() {
            return 0;
        }

        @Override
        public int pruneAuditLog(int days) {
            return 0;
        }

        @Override
        public Optional<String> getUserPreferences(String username) {
            return Optional.empty();
        }

        @Override
        public void saveUserPreferences(String username, String preferencesJson) {}

        @Override
        public void registerNode(String nodeId) {}

        @Override
        public Optional<RegisteredNode> getRegisteredNode(String nodeId) {
            return Optional.empty();
        }

        @Override
        public List<RegisteredNode> getAllRegisteredNodes() {
            return List.of();
        }

        @Override
        public void updateNodeLastSeen(String nodeId) {}

        @Override
        public void deleteRegisteredNode(String nodeId) {}

        @Override
        public void saveTransferIntent(TransferIntent intent) {}

        @Override
        public List<TransferIntent> getTransferIntents() {
            return List.of();
        }

        @Override
        public void deleteTransferIntent(UUID playerUuid) {}

        @Override
        public void saveNodeDrainIntent(NodeDrainIntent intent) {}

        @Override
        public List<NodeDrainIntent> getNodeDrainIntents() {
            return List.of();
        }

        @Override
        public void deleteNodeDrainIntent(String nodeId) {}

        @Override
        public void saveHealingActionIntent(HealingActionIntent intent) {}

        @Override
        public List<HealingActionIntent> getHealingActionIntents() {
            return List.of();
        }

        @Override
        public void deleteHealingActionIntent(String instanceId) {}

        @Override
        public void saveStartRetryIntent(StartRetryIntent intent) {}

        @Override
        public List<StartRetryIntent> getStartRetryIntents() {
            return List.of();
        }

        @Override
        public void deleteStartRetryIntent(String instanceId) {}

        @Override
        public void saveInstanceCompositionPlan(InstanceCompositionPlan plan) {}

        @Override
        public Optional<InstanceCompositionPlan> getInstanceCompositionPlan(String instanceId) {
            return Optional.empty();
        }

        @Override
        public void deleteInstanceCompositionPlan(String instanceId) {}
    }
}
