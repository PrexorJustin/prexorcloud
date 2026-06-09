package me.prexorjustin.prexorcloud.modules.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import me.prexorjustin.prexorcloud.harness.module.ModuleTestHarness;
import me.prexorjustin.prexorcloud.modules.example.data.PlaytimeRepository;
import me.prexorjustin.prexorcloud.modules.example.data.Session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration of {@code example-playtime} against a hermetic
 * controller stack provided by {@link ModuleTestHarness}.
 *
 * <p>Each test boots ephemeral MongoDB + Redis containers and an in-process
 * controller — no shared local services. Skipped automatically when Docker
 * isn't reachable so dev machines without a daemon don't see failures.
 */
final class ExampleModuleIntegrationTest {

    private static final String MODULE_ID = "example-playtime";

    @Test
    void playtimeRepositoryRoundTripsAgainstRealMongo() throws Exception {
        assumeTrue(ModuleTestHarness.isDockerAvailable(), "Docker required for integration test");

        try (var harness = ModuleTestHarness.start()) {
            var repo = new PlaytimeRepository(harness.dataStoreFor(MODULE_ID));

            UUID playerId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            Instant joinAt = Instant.parse("2026-05-12T10:00:00Z");
            repo.openSession(new Session(playerId, sessionId, joinAt, null, 0L, "lobby"));

            var sessions = repo.recentSessions(playerId, 10);
            assertEquals(1, sessions.size());
            assertEquals(sessionId, sessions.getFirst().sessionId());
            assertEquals("lobby", sessions.getFirst().serverName());
        }
    }

    @Test
    void topEndpointReturnsRankedLeaderboardAfterInstall() throws Exception {
        assumeTrue(ModuleTestHarness.isDockerAvailable(), "Docker required for integration test");

        String jarPath = System.getProperty("example.shadowjar.path");
        assertNotNull(jarPath, "build.gradle.kts must set example.shadowjar.path");

        try (var harness = ModuleTestHarness.start()) {
            harness.installFromJar(Path.of(jarPath));

            // Seed two players whose totals will rank deterministically. The
            // harness data-store view and the module's own data-store view
            // share the underlying collections (both use the
            // `mod_example-playtime_` prefix), so the rebuilt `totals`
            // collection is visible to /top inside the module's classloader.
            var repo = new PlaytimeRepository(harness.dataStoreFor(MODULE_ID));
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            Instant now = Instant.parse("2026-05-12T10:00:00Z");
            repo.openSession(new Session(
                    alice, UUID.randomUUID(), now.minusSeconds(7200), now.minusSeconds(3600), 3_600_000L, "lobby"));
            repo.openSession(new Session(bob, UUID.randomUUID(), now.minusSeconds(3600), now, 1_800_000L, "lobby"));
            repo.rebuildTotals();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(harness.restBaseUrl() + "/api/v1/modules/" + MODULE_ID + "/top"))
                    .header("Authorization", "Bearer " + harness.adminJwt())
                    .GET()
                    .build();
            HttpResponse<String> response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), () -> "body: " + response.body());

            var json = new ObjectMapper().readTree(response.body());
            assertEquals(2, json.get("count").asInt());
            assertEquals(
                    alice.toString(),
                    json.get("items").get(0).get("playerId").asText(),
                    "alice (3.6M ms) outranks bob (1.8M ms)");
            assertEquals(3_600_000L, json.get("items").get(0).get("totalMs").asLong());
        }
    }
}
