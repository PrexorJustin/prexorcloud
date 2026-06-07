package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleLifecycleManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end install + REST drive of the first-party {@code stats-aggregator} reference
 * module against a live controller. Closes the §8 "Open at v1" gate that the
 * stats-aggregator install path works against a fresh stack without manual config edits,
 * and incidentally exercises the controller's module REST dispatcher (the wildcard handler
 * mounted at {@code /api/v1/modules/{moduleId}/<sub>}).
 */
class StatsAggregatorInstallTest {

    static TestCluster cluster;
    static RestClient admin;
    static Path moduleJar;
    static Path playerJourneyJar;

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(
                TestCluster.mongoAvailable(), "MongoDB is required for stats-aggregator install integration");
        String configured = System.getProperty("prexor.test.statsAggregatorJar");
        Assumptions.assumeTrue(
                configured != null && !configured.isBlank(),
                "prexor.test.statsAggregatorJar system property must be set (gradle test task wires it via the shadowJar artifact)");
        moduleJar = Path.of(configured);
        Assumptions.assumeTrue(Files.exists(moduleJar), "stats-aggregator shadowJar not found at " + moduleJar);

        String journeyConfigured = System.getProperty("prexor.test.playerJourneyJar");
        Assumptions.assumeTrue(
                journeyConfigured != null && !journeyConfigured.isBlank(),
                "prexor.test.playerJourneyJar system property must be set — stats-aggregator's prexor.player.journey requirement is satisfied by the player-journey module post-Layer-5");
        playerJourneyJar = Path.of(journeyConfigured);
        Assumptions.assumeTrue(
                Files.exists(playerJourneyJar), "player-journey shadowJar not found at " + playerJourneyJar);

        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    @Test
    void installsReferenceModuleAndServesRestRoutes() throws Exception {
        // 1a. Install the player-journey module first — Layer 5 dropped the
        //     controller's built-in prexor.player.journey handle, so the
        //     module is now the only provider for stats-aggregator's
        //     "requires" entry.
        var journeyInstall = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", playerJourneyJar);
        assertEquals(201, journeyInstall.status(), journeyInstall.body());
        assertEquals("player-journey", journeyInstall.json().get("moduleId").asText());
        cluster.waitForCondition(
                "player-journey activation",
                10_000,
                () -> cluster.controller()
                        .moduleRegistry()
                        .platformManager()
                        .snapshot("player-journey")
                        .map(snapshot -> snapshot.state() == ModuleLifecycleManager.ModuleState.ACTIVE)
                        .orElse(false));

        // 1b. Upload the real first-party stats-aggregator shadowJar.
        var install = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", moduleJar);
        assertEquals(201, install.status(), install.body());
        assertEquals("stats-aggregator", install.json().get("moduleId").asText());

        // 2. Module reaches ACTIVE — its prexor.player.journey requirement
        //    resolves against the player-journey module installed above.
        cluster.waitForCondition(
                "stats-aggregator activation",
                10_000,
                () -> cluster.controller()
                        .moduleRegistry()
                        .platformManager()
                        .snapshot("stats-aggregator")
                        .map(snapshot -> snapshot.state() == ModuleLifecycleManager.ModuleState.ACTIVE)
                        .orElse(false));

        // 3. Empty leaderboard responds with 200 + zero-length list.
        var emptyTop = admin.get("/api/v1/modules/stats-aggregator/players/top");
        assertEquals(200, emptyTop.status(), emptyTop.body());
        assertEquals(0, emptyTop.json().get("count").asInt());

        // 4. Drive a session through join → leave → rebuild.
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant joinAt = Instant.now().minusSeconds(120);
        Instant quitAt = joinAt.plusSeconds(60);

        var join = admin.post(
                "/api/v1/modules/stats-aggregator/sessions/join",
                Map.of(
                        "playerId",
                        playerId.toString(),
                        "playerName",
                        "tester",
                        "sessionId",
                        sessionId.toString(),
                        "group",
                        "lobby",
                        "instanceId",
                        "lobby-001",
                        "joinAt",
                        joinAt.toString()));
        assertEquals(202, join.status(), join.body());

        var leave = admin.post(
                "/api/v1/modules/stats-aggregator/sessions/leave",
                Map.of(
                        "sessionId", sessionId.toString(),
                        "quitAt", quitAt.toString(),
                        "durationMs", 60_000L));
        assertEquals(202, leave.status(), leave.body());

        var rebuild = admin.postEmpty("/api/v1/modules/stats-aggregator/aggregates/rebuild");
        assertEquals(200, rebuild.status(), rebuild.body());
        assertTrue(rebuild.json().get("players").asInt() >= 1, rebuild.body());

        // 5. The closed session shows up on the leaderboard.
        var populatedTop = admin.get("/api/v1/modules/stats-aggregator/players/top?limit=10");
        assertEquals(200, populatedTop.status(), populatedTop.body());
        assertTrue(populatedTop.json().get("count").asInt() >= 1, populatedTop.body());

        // 6. Per-player detail route resolves the same player UUID via path-param dispatch.
        var detail = admin.get("/api/v1/modules/stats-aggregator/players/" + playerId);
        assertEquals(200, detail.status(), detail.body());
        assertNotNull(detail.json().get("playerStat"), detail.body());

        // 7. Prometheus exposition format is reachable through the dispatcher.
        var metrics = admin.get("/api/v1/modules/stats-aggregator/metrics");
        assertEquals(200, metrics.status(), metrics.body());
        assertTrue(metrics.body().contains("stats_aggregator"), metrics.body());

        // 8. Uninstall must drop the module's routes (404 afterward).
        var uninstall = admin.delete("/api/v1/modules/platform/stats-aggregator");
        assertEquals(204, uninstall.status(), uninstall.body());
        var gone = admin.get("/api/v1/modules/stats-aggregator/players/top");
        assertEquals(404, gone.status(), gone.body());
    }
}
