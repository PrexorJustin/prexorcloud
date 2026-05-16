package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import me.prexorjustin.prexorcloud.harness.PlatformModuleTestJarFactory;
import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleLifecycleManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModuleLifecycleTest {

    static TestCluster cluster;
    static RestClient admin;

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for platform module harness tests");
        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    @Test
    void installUpgradeUninstallRebindsCapabilitiesWithoutControllerRestart(@TempDir Path tempDir) throws Exception {
        System.setProperty(PlatformModuleTestJarFactory.EVENT_DIR_PROPERTY, tempDir.toString());
        try {
            Path consumerJar = PlatformModuleTestJarFactory.createConsumerJar(tempDir.resolve("queue.jar"));
            Path providerV1Jar = PlatformModuleTestJarFactory.createProviderV1Jar(tempDir.resolve("profile-v1.jar"));
            Path providerV2Jar = PlatformModuleTestJarFactory.createProviderV2Jar(tempDir.resolve("profile-v2.jar"));

            var consumerInstall = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", consumerJar);
            assertEquals(201, consumerInstall.status(), consumerInstall.body());
            assertEquals(
                    ModuleLifecycleManager.ModuleState.WAITING.name(),
                    cluster.controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("queue")
                            .orElseThrow()
                            .state()
                            .name());

            var providerInstall = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", providerV1Jar);
            assertEquals(201, providerInstall.status(), providerInstall.body());
            assertEquals(
                    ModuleLifecycleManager.ModuleState.ACTIVE.name(),
                    cluster.controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("queue")
                            .orElseThrow()
                            .state()
                            .name());

            var providerUpgrade =
                    admin.postMultipartFile("/api/v1/modules/platform/profile/upgrade", "file", providerV2Jar);
            assertEquals(200, providerUpgrade.status(), providerUpgrade.body());
            assertEquals(
                    "2.0.0",
                    cluster.controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("profile")
                            .orElseThrow()
                            .version());
            assertEquals(
                    ModuleLifecycleManager.ModuleState.ACTIVE.name(),
                    cluster.controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("queue")
                            .orElseThrow()
                            .state()
                            .name());
            cluster.waitForCondition(
                    "capability hot-swap to profile-v2",
                    10_000,
                    () -> hasEvent(tempDir, "consumer.log", "rebind:profile-v2"));

            var providerDelete = admin.delete("/api/v1/modules/platform/profile");
            assertEquals(204, providerDelete.status(), providerDelete.body());
            assertEquals(
                    ModuleLifecycleManager.ModuleState.WAITING.name(),
                    cluster.controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("queue")
                            .orElseThrow()
                            .state()
                            .name());

            assertIterableEquals(
                    java.util.List.of("load", "start:profile-v1", "rebind:profile-v2", "stop"),
                    PlatformModuleTestJarFactory.readEvents(tempDir, "consumer.log"));
            assertIterableEquals(
                    java.util.List.of(
                            "v1:load",
                            "v1:start",
                            "v1:stop",
                            "v1:unload",
                            "v2:load",
                            "v2:upgrade:1.0.0",
                            "v2:start",
                            "v2:stop",
                            "v2:unload"),
                    PlatformModuleTestJarFactory.readEvents(tempDir, "provider.log"));

            var queueSnapshot = cluster.controller()
                    .moduleRegistry()
                    .platformManager()
                    .snapshot("queue")
                    .orElseThrow();
            assertTrue(queueSnapshot.unresolvedRequirements().stream()
                    .anyMatch(requirement -> requirement.capabilityId().equals("player-profile")));
        } finally {
            System.clearProperty(PlatformModuleTestJarFactory.EVENT_DIR_PROPERTY);
        }
    }

    @Test
    void moduleWithFrontendAndMultiVersionExtensionsInstallsAndComposesExactVariant(@TempDir Path tempDir)
            throws Exception {
        Path moduleJar = PlatformModuleTestJarFactory.createFrontendExtensionJar(tempDir.resolve("ops-ui.jar"));

        var uploadResponse = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", moduleJar);
        assertEquals(201, uploadResponse.status(), uploadResponse.body());
        assertEquals("ops-ui", uploadResponse.json().get("moduleId").asText());
        assertEquals(
                "index.js", uploadResponse.json().get("frontend").get("entry").asText());
        assertEquals(2, uploadResponse.json().get("extensions").size());

        var modulesResponse = admin.get("/api/v1/modules");
        assertEquals(200, modulesResponse.status(), modulesResponse.body());
        assertTrue(modulesResponse.asList().stream().anyMatch(module -> "ops-ui".equals(module.get("name"))));

        var assetResponse = admin.get("/api/v1/modules/ops-ui/frontend/index.js");
        assertEquals(200, assetResponse.status(), assetResponse.body());
        assertTrue(assetResponse.body().contains("ops-ui frontend"));

        String groupName = "ops-ui-acceptance";
        var groupCreate = admin.post(
                "/api/v1/groups",
                java.util.Map.of(
                        "name",
                        groupName,
                        "platform",
                        "PAPER",
                        "platformVersion",
                        "1.21.1",
                        "minInstances",
                        0,
                        "maxInstances",
                        1,
                        "maxPlayers",
                        25,
                        "attachedExtensions",
                        java.util.List.of("ops-matchmaking"),
                        "disabledExtensions",
                        java.util.List.of("ops-default")));
        assertEquals(201, groupCreate.status(), groupCreate.body());

        var startResponse = admin.postEmpty("/api/v1/groups/" + groupName + "/start");
        assertEquals(200, startResponse.status(), startResponse.body());

        cluster.waitForCondition(
                "scheduled acceptance instance for " + groupName,
                15_000,
                () -> !cluster.controller()
                        .clusterState()
                        .getInstancesByGroup(groupName)
                        .isEmpty());

        String instanceId = cluster.controller()
                .clusterState()
                .getInstancesByGroup(groupName)
                .getFirst()
                .id();
        cluster.waitForCondition(
                "persisted composition plan for " + instanceId,
                15_000,
                () -> cluster.controller()
                        .stateStore()
                        .getInstanceCompositionPlan(instanceId)
                        .isPresent());

        var compositionResponse = admin.get("/api/v1/services/" + instanceId + "/composition");
        assertEquals(200, compositionResponse.status(), compositionResponse.body());
        assertEquals(instanceId, compositionResponse.json().get("instanceId").asText());
        assertEquals(1, compositionResponse.json().get("extensions").size());
        assertEquals(
                "ops-matchmaking",
                compositionResponse
                        .json()
                        .get("extensions")
                        .get(0)
                        .get("extensionId")
                        .asText());
        assertEquals(
                "paper-1-21-1",
                compositionResponse
                        .json()
                        .get("extensions")
                        .get(0)
                        .get("variantId")
                        .asText());
        assertTrue(compositionResponse
                .json()
                .get("extensions")
                .get(0)
                .get("downloadUrl")
                .asText()
                .endsWith("/extensions/paper/ops-matchmaking-1.21.1.jar"));
    }

    @Test
    void moduleStorageIsIsolatedPerOwnerAndSurvivesControllerReload(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(TestCluster.redisAvailable(), "Redis is required for platform storage harness tests");
        System.setProperty(PlatformModuleTestJarFactory.EVENT_DIR_PROPERTY, tempDir.toString());
        try (TestCluster storageCluster = TestCluster.startWithRedis()) {
            RestClient storageAdmin = new RestClient(storageCluster.restBaseUrl(), storageCluster.adminJwtToken());

            Path alphaJar = PlatformModuleTestJarFactory.createStorageProbeJar(
                    tempDir.resolve("storage-alpha.jar"), "storage-alpha");
            Path betaJar = PlatformModuleTestJarFactory.createStorageProbeJar(
                    tempDir.resolve("storage-beta.jar"), "storage-beta");

            var alphaInstall = storageAdmin.postMultipartFile("/api/v1/modules/platform/upload", "file", alphaJar);
            var betaInstall = storageAdmin.postMultipartFile("/api/v1/modules/platform/upload", "file", betaJar);
            assertEquals(201, alphaInstall.status(), alphaInstall.body());
            assertEquals(201, betaInstall.status(), betaInstall.body());

            storageCluster.waitForCondition(
                    "storage-alpha boot 1",
                    15_000,
                    () -> hasEventWithFragments(
                            tempDir, "storage-alpha.log", "mongo=storage-alpha", "redis=storage-alpha", "boot=1"));
            storageCluster.waitForCondition(
                    "storage-beta boot 1",
                    15_000,
                    () -> hasEventWithFragments(
                            tempDir, "storage-beta.log", "mongo=storage-beta", "redis=storage-beta", "boot=1"));

            var alphaSnapshot = storageCluster
                    .controller()
                    .moduleRegistry()
                    .platformManager()
                    .snapshot("storage-alpha")
                    .orElseThrow();
            var betaSnapshot = storageCluster
                    .controller()
                    .moduleRegistry()
                    .platformManager()
                    .snapshot("storage-beta")
                    .orElseThrow();
            assertTrue(alphaSnapshot.storage().mongoAvailable());
            assertTrue(alphaSnapshot.storage().redisAvailable());
            assertTrue(betaSnapshot.storage().mongoAvailable());
            assertTrue(betaSnapshot.storage().redisAvailable());
            assertNotEquals(
                    alphaSnapshot.storage().mongoCollectionPrefix(),
                    betaSnapshot.storage().mongoCollectionPrefix());
            assertNotEquals(
                    alphaSnapshot.storage().redisKeyPrefix(),
                    betaSnapshot.storage().redisKeyPrefix());

            assertTrue(PlatformModuleTestJarFactory.readEvents(tempDir, "storage-alpha.log").stream()
                    .allMatch(line -> line.contains("storage-alpha") && !line.contains("storage-beta")));
            assertTrue(PlatformModuleTestJarFactory.readEvents(tempDir, "storage-beta.log").stream()
                    .allMatch(line -> line.contains("storage-beta") && !line.contains("storage-alpha")));

            storageCluster.restartController();

            storageCluster.waitForCondition(
                    "storage-alpha boot 2",
                    15_000,
                    () -> hasEventWithFragments(
                            tempDir, "storage-alpha.log", "mongo=storage-alpha", "redis=storage-alpha", "boot=2"));
            storageCluster.waitForCondition(
                    "storage-beta boot 2",
                    15_000,
                    () -> hasEventWithFragments(
                            tempDir, "storage-beta.log", "mongo=storage-beta", "redis=storage-beta", "boot=2"));
        } finally {
            System.clearProperty(PlatformModuleTestJarFactory.EVENT_DIR_PROPERTY);
        }
    }

    @Test
    void controllerRestartReloadsStoredPlatformModulesAndCapabilities(@TempDir Path tempDir) throws Exception {
        System.setProperty(PlatformModuleTestJarFactory.EVENT_DIR_PROPERTY, tempDir.toString());
        try {
            Path consumerJar = PlatformModuleTestJarFactory.createConsumerJar(tempDir.resolve("queue-restart.jar"));
            Path providerJar = PlatformModuleTestJarFactory.createProviderV1Jar(tempDir.resolve("profile-restart.jar"));

            var consumerInstall = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", consumerJar);
            assertEquals(201, consumerInstall.status(), consumerInstall.body());

            var providerInstall = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", providerJar);
            assertEquals(201, providerInstall.status(), providerInstall.body());

            assertEquals(
                    ModuleLifecycleManager.ModuleState.ACTIVE.name(),
                    cluster.controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("queue")
                            .orElseThrow()
                            .state()
                            .name());
            assertEquals(
                    ModuleLifecycleManager.ModuleState.ACTIVE.name(),
                    cluster.controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("profile")
                            .orElseThrow()
                            .state()
                            .name());

            cluster.restartController();
            admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
            cluster.waitForCondition(
                    "platform module reload after controller restart",
                    15_000,
                    () -> cluster.controller()
                                    .moduleRegistry()
                                    .platformManager()
                                    .snapshot("queue")
                                    .map(snapshot -> snapshot.state() == ModuleLifecycleManager.ModuleState.ACTIVE)
                                    .orElse(false)
                            && cluster.controller()
                                    .moduleRegistry()
                                    .platformManager()
                                    .snapshot("profile")
                                    .map(snapshot -> snapshot.state() == ModuleLifecycleManager.ModuleState.ACTIVE)
                                    .orElse(false));

            var listResponse = admin.get("/api/v1/modules/platform");
            assertEquals(200, listResponse.status(), listResponse.body());
            assertTrue(listResponse.asList().stream().anyMatch(module -> "queue".equals(module.get("moduleId"))));
            assertTrue(listResponse.asList().stream().anyMatch(module -> "profile".equals(module.get("moduleId"))));

            var queueSnapshot = cluster.controller()
                    .moduleRegistry()
                    .platformManager()
                    .snapshot("queue")
                    .orElseThrow();
            var profileSnapshot = cluster.controller()
                    .moduleRegistry()
                    .platformManager()
                    .snapshot("profile")
                    .orElseThrow();

            assertEquals(ModuleLifecycleManager.ModuleState.ACTIVE, queueSnapshot.state());
            assertEquals(ModuleLifecycleManager.ModuleState.ACTIVE, profileSnapshot.state());
            assertEquals("1.0.0", profileSnapshot.version());
            assertNotNull(cluster.controller()
                    .moduleRegistry()
                    .platformManager()
                    .capabilityRegistry()
                    .find("player-profile")
                    .orElse(null));

            var consumerEvents = PlatformModuleTestJarFactory.readEvents(tempDir, "consumer.log");
            var providerEvents = PlatformModuleTestJarFactory.readEvents(tempDir, "provider.log");
            assertTrue(
                    java.util.Collections.frequency(consumerEvents, "load") >= 2,
                    "consumer should load before and after restart: " + consumerEvents);
            assertTrue(
                    java.util.Collections.frequency(consumerEvents, "start:profile-v1") >= 2,
                    "consumer should bind capability before and after restart: " + consumerEvents);
            assertTrue(
                    java.util.Collections.frequency(providerEvents, "v1:load") >= 2,
                    "provider should load before and after restart: " + providerEvents);
            assertTrue(
                    java.util.Collections.frequency(providerEvents, "v1:start") >= 2,
                    "provider should start before and after restart: " + providerEvents);

            admin.delete("/api/v1/modules/platform/queue");
            admin.delete("/api/v1/modules/platform/profile");
        } finally {
            System.clearProperty(PlatformModuleTestJarFactory.EVENT_DIR_PROPERTY);
        }
    }

    @Test
    void standbyControllerFailoverReloadsStoredPlatformModulesAndCapabilities(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for platform module harness tests");
        Assumptions.assumeTrue(TestCluster.redisAvailable(), "Redis is required for HA platform module harness tests");

        System.setProperty(PlatformModuleTestJarFactory.EVENT_DIR_PROPERTY, tempDir.toString());
        try (TestCluster haCluster = TestCluster.startWithRedisHa(0, 2)) {
            RestClient activeAdmin = new RestClient(haCluster.restBaseUrl(), haCluster.adminJwtToken());
            Path consumerJar = PlatformModuleTestJarFactory.createConsumerJar(tempDir.resolve("queue-ha.jar"));
            Path providerJar = PlatformModuleTestJarFactory.createProviderV1Jar(tempDir.resolve("profile-ha.jar"));

            var consumerInstall = activeAdmin.postMultipartFile("/api/v1/modules/platform/upload", "file", consumerJar);
            assertEquals(201, consumerInstall.status(), consumerInstall.body());

            var providerInstall = activeAdmin.postMultipartFile("/api/v1/modules/platform/upload", "file", providerJar);
            assertEquals(201, providerInstall.status(), providerInstall.body());

            assertEquals(
                    ModuleLifecycleManager.ModuleState.ACTIVE.name(),
                    haCluster
                            .controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("queue")
                            .orElseThrow()
                            .state()
                            .name());

            haCluster.failoverController();
            RestClient standbyAdmin = new RestClient(haCluster.restBaseUrl(), haCluster.adminJwtToken());
            haCluster.waitForCondition(
                    "platform module reload after standby promotion",
                    15_000,
                    () -> haCluster
                                    .controller()
                                    .moduleRegistry()
                                    .platformManager()
                                    .snapshot("queue")
                                    .map(snapshot -> snapshot.state() == ModuleLifecycleManager.ModuleState.ACTIVE)
                                    .orElse(false)
                            && haCluster
                                    .controller()
                                    .moduleRegistry()
                                    .platformManager()
                                    .snapshot("profile")
                                    .map(snapshot -> snapshot.state() == ModuleLifecycleManager.ModuleState.ACTIVE)
                                    .orElse(false));

            var listResponse = standbyAdmin.get("/api/v1/modules/platform");
            assertEquals(200, listResponse.status(), listResponse.body());
            assertTrue(listResponse.asList().stream().anyMatch(module -> "queue".equals(module.get("moduleId"))));
            assertTrue(listResponse.asList().stream().anyMatch(module -> "profile".equals(module.get("moduleId"))));
            assertNotNull(haCluster
                    .controller()
                    .moduleRegistry()
                    .platformManager()
                    .capabilityRegistry()
                    .find("player-profile")
                    .orElse(null));

            var consumerEvents = PlatformModuleTestJarFactory.readEvents(tempDir, "consumer.log");
            var providerEvents = PlatformModuleTestJarFactory.readEvents(tempDir, "provider.log");
            assertTrue(
                    java.util.Collections.frequency(consumerEvents, "load") >= 2,
                    "consumer should load before and after failover: " + consumerEvents);
            assertTrue(
                    java.util.Collections.frequency(consumerEvents, "start:profile-v1") >= 2,
                    "consumer should bind capability before and after failover: " + consumerEvents);
            assertTrue(
                    java.util.Collections.frequency(providerEvents, "v1:load") >= 2,
                    "provider should load before and after failover: " + providerEvents);
            assertTrue(
                    java.util.Collections.frequency(providerEvents, "v1:start") >= 2,
                    "provider should start before and after failover: " + providerEvents);
        } finally {
            System.clearProperty(PlatformModuleTestJarFactory.EVENT_DIR_PROPERTY);
        }
    }

    @Test
    void installThenUninstallReleasesModuleClassLoader(@TempDir Path tempDir) throws Exception {
        // Phase 31 acceptance: a clean install→unload cycle must let the module's
        // classloader become eligible for GC. The tracker is the single source of
        // truth — we install one provider module, uninstall it, then drive the
        // tracker until totalCollected catches up. The poll loop is bounded; on a
        // slow CI the leak threshold may fire before GC, but the assertion only
        // cares about totalCollected progressing past zero.
        System.setProperty(PlatformModuleTestJarFactory.EVENT_DIR_PROPERTY, tempDir.toString());
        try {
            Path providerJar =
                    PlatformModuleTestJarFactory.createProviderV1Jar(tempDir.resolve("collect-profile-v1.jar"));

            var tracker =
                    cluster.controller().moduleRegistry().platformManager().classLoaderTracker();
            long collectedBefore = tracker.totalCollected();

            var install = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", providerJar);
            assertEquals(201, install.status(), install.body());
            assertEquals(
                    "profile",
                    cluster.controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("profile")
                            .orElseThrow()
                            .moduleId());

            var delete = admin.delete("/api/v1/modules/platform/profile");
            assertEquals(204, delete.status(), delete.body());

            long deadline = System.currentTimeMillis() + 30_000;
            while (tracker.totalCollected() <= collectedBefore && System.currentTimeMillis() < deadline) {
                tracker.requestForcedCleanup();
                Thread.sleep(100);
            }

            assertTrue(
                    tracker.totalCollected() > collectedBefore,
                    "module classloader was not GC'd within 30s of uninstall (pending=" + tracker.pendingCount()
                            + ", leaks=" + tracker.totalLeaks() + ")");
        } finally {
            System.clearProperty(PlatformModuleTestJarFactory.EVENT_DIR_PROPERTY);
        }
    }

    private static boolean hasEvent(Path eventsDir, String fileName, String expectedEvent) {
        try {
            return PlatformModuleTestJarFactory.readEvents(eventsDir, fileName).contains(expectedEvent);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read platform module events", e);
        }
    }

    private static boolean hasEventWithFragments(Path eventsDir, String fileName, String... fragments) {
        try {
            return PlatformModuleTestJarFactory.readEvents(eventsDir, fileName).stream()
                    .anyMatch(line -> java.util.Arrays.stream(fragments).allMatch(line::contains));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read platform module events", e);
        }
    }
}
