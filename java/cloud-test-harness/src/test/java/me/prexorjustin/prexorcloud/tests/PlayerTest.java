package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/players/* endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlayerTest {

    static TestCluster cluster;
    static RestClient admin;

    @BeforeAll
    static void setup() throws Exception {
        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    // --- GET /api/v1/players (empty) ---

    @Test
    @Order(1)
    void listPlayers_empty_returnsEmptyList() throws Exception {
        var resp = admin.get("/api/v1/players");
        assertEquals(200, resp.status());
        assertTrue(resp.asList().isEmpty());
    }

    // --- GET /api/v1/players/{id} ---

    @Test
    @Order(10)
    void getPlayer_invalidUuid_returns400() throws Exception {
        var resp = admin.get("/api/v1/players/not-a-uuid");
        assertEquals(400, resp.status());
    }

    @Test
    @Order(11)
    void getPlayer_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/players/" + UUID.randomUUID());
        assertEquals(404, resp.status());
    }

    // --- POST /api/v1/players/{id}/transfer ---

    @Test
    @Order(20)
    void transferPlayer_playerNotFound_returns404() throws Exception {
        var resp = admin.post(
                "/api/v1/players/" + UUID.randomUUID() + "/transfer", Map.of("targetInstanceId", "some-instance"));
        assertEquals(404, resp.status());
    }

    @Test
    @Order(21)
    void transferPlayer_invalidUuid_returns400() throws Exception {
        var resp = admin.post("/api/v1/players/bad-uuid/transfer", Map.of("targetInstanceId", "some-instance"));
        assertEquals(400, resp.status());
    }
}
