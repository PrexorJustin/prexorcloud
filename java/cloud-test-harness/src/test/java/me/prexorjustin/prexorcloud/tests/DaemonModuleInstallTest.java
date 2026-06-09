package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleLifecycleManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage of the daemon-host platform-module pipeline (Layer 7):
 * upload → controller distributor → daemon receives ModuleInstall → DaemonModuleManager
 * commits and activates → MODULE_STATE_UPDATE=ACTIVE → daemon-module lifecycle hooks
 * fire (recorded by {@code TestDaemonModule} to a hooks file).
 */
@Tag("daemon-module")
class DaemonModuleInstallTest {

    static TestCluster cluster;
    static RestClient admin;
    static Path moduleJar;
    static Path hooksFile;

    @BeforeAll
    static void setup(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(
                TestCluster.mongoAvailable(), "MongoDB is required for daemon-module install integration");
        String configured = System.getProperty("prexor.test.testDaemonModuleJar");
        Assumptions.assumeTrue(
                configured != null && !configured.isBlank(),
                "prexor.test.testDaemonModuleJar must be set by the gradle test task");
        moduleJar = Path.of(configured);
        Assumptions.assumeTrue(Files.exists(moduleJar), "test-daemon-module jar not found: " + moduleJar);

        hooksFile = tmp.resolve("test-daemon-hooks.log");
        System.setProperty(
                "prexor.test.testDaemonModuleHooksFile",
                hooksFile.toAbsolutePath().toString());

        cluster = TestCluster.start(1);
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
        cluster.waitForNode("test-daemon-1", 15_000);
    }

    @AfterAll
    static void teardown() {
        System.clearProperty("prexor.test.testDaemonModuleHooksFile");
        if (cluster != null) cluster.close();
    }

    @Test
    void installDistributesToDaemonAndFiresLifecycleHooks() throws Exception {
        // 1. Upload to controller.
        var install = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", moduleJar);
        assertEquals(201, install.status(), install.body());
        assertEquals("test-daemon-module", install.json().get("moduleId").asText());

        // 2. Controller-side state. Daemon-only modules don't activate on the controller —
        //    the manager just records them as INSTALLED and ships them to daemons.
        cluster.waitForCondition(
                "daemon-host module recorded by controller",
                10_000,
                () -> cluster.controller()
                        .moduleRegistry()
                        .platformManager()
                        .snapshot("test-daemon-module")
                        .isPresent());

        // 3. Daemon-side state should reach ACTIVE via the gRPC distributor path.
        var daemon = cluster.daemons().get(0);
        cluster.waitForCondition(
                "test-daemon-module ACTIVE on daemon",
                10_000,
                () -> daemon.daemonModuleManager()
                        .moduleState("test-daemon-module")
                        .map(state -> state == ModuleLifecycleManager.ModuleState.ACTIVE)
                        .orElse(false));

        // 4. Lifecycle hooks recorded by TestDaemonModule.
        cluster.waitForCondition(
                "onLoad+onStart recorded", 10_000, () -> readHooksOrEmpty().contains("onStart test-daemon-module"));
        String afterStart = readHooksOrEmpty();
        assertTrue(afterStart.contains("onLoad test-daemon-module"), afterStart);
        assertTrue(afterStart.contains("onStart test-daemon-module"), afterStart);

        // 5. Uninstall — daemon receives ModuleUninstall, runs onStop+onUnload.
        var uninstall = admin.delete("/api/v1/modules/platform/test-daemon-module");
        assertEquals(204, uninstall.status(), uninstall.body());
        cluster.waitForCondition(
                "test-daemon-module unloaded on daemon",
                10_000,
                () -> daemon.daemonModuleManager()
                                .moduleState("test-daemon-module")
                                .isEmpty()
                        || daemon.daemonModuleManager()
                                .moduleState("test-daemon-module")
                                .map(state -> state == ModuleLifecycleManager.ModuleState.UNLOADED)
                                .orElse(false));
        cluster.waitForCondition(
                "onStop+onUnload recorded",
                10_000,
                () -> readHooksOrEmpty().contains("onStop test-daemon-module")
                        && readHooksOrEmpty().contains("onUnload test-daemon-module"));
    }

    private static String readHooksOrEmpty() {
        try {
            return Files.exists(hooksFile) ? Files.readString(hooksFile) : "";
        } catch (java.io.IOException _) {
            return "";
        }
    }
}
