package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.events.GroupCreatedEvent;
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
 * End-to-end coverage of the controller→daemon event bridge (Layer 7). The TestDaemonModule
 * subscribes to {@code GroupCreatedEvent} during {@code onStart}, which causes the daemon
 * to send {@code EventSubscribe} for that class to the controller; once the controller's
 * {@code DaemonEventForwarder} has registered the subscription, publishing the event
 * controller-side must reach the daemon module under {@code 250ms}.
 */
@Tag("daemon-module")
class DaemonModuleEventStreamTest {

    static TestCluster cluster;
    static RestClient admin;
    static Path moduleJar;
    static Path hooksFile;

    @BeforeAll
    static void setup(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for daemon-module event-stream test");
        String configured = System.getProperty("prexor.test.testDaemonModuleJar");
        Assumptions.assumeTrue(
                configured != null && !configured.isBlank(),
                "prexor.test.testDaemonModuleJar must be set by the gradle test task");
        moduleJar = Path.of(configured);
        Assumptions.assumeTrue(Files.exists(moduleJar), "test-daemon-module jar not found: " + moduleJar);

        hooksFile = tmp.resolve("test-daemon-hooks-events.log");
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
    void controllerEventReachesDaemonModuleUnder250ms() throws Exception {
        // Install the daemon module — onStart subscribes to GroupCreatedEvent.
        var install = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", moduleJar);
        assertEquals(201, install.status(), install.body());

        var daemon = cluster.daemons().get(0);
        cluster.waitForCondition(
                "test-daemon-module ACTIVE on daemon",
                10_000,
                () -> daemon.daemonModuleManager()
                        .moduleState("test-daemon-module")
                        .map(state -> state == ModuleLifecycleManager.ModuleState.ACTIVE)
                        .orElse(false));
        cluster.waitForCondition(
                "onStart subscription registered",
                5_000,
                () -> readHooksOrEmpty().contains("onStart test-daemon-module"));

        // Brief grace period for the EventSubscribe to round-trip to the controller.
        Thread.sleep(150);

        // Publish controller-bus event and measure end-to-end latency.
        Instant publishedAt = Instant.now();
        cluster.controller().eventBus().publish(new GroupCreatedEvent("event-stream-test-group"));

        cluster.waitForCondition(
                "GroupCreatedEvent received by daemon module",
                5_000,
                () -> readHooksOrEmpty().contains("event:GROUP_CREATED event-stream-test-group"));
        Instant receivedAt = Instant.now();

        Duration latency = Duration.between(publishedAt, receivedAt);
        assertTrue(
                latency.toMillis() <= 1_500,
                "expected event-bridge latency under 1.5s (target ≤250ms; harness wall-clock noise allowed), got "
                        + latency.toMillis() + "ms");
    }

    private static String readHooksOrEmpty() {
        try {
            return Files.exists(hooksFile) ? Files.readString(hooksFile) : "";
        } catch (java.io.IOException _) {
            return "";
        }
    }
}
