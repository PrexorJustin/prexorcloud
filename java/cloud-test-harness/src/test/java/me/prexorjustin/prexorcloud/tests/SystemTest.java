package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/system/*, /api/v1/overview, /api/v1/metrics/*.
 */
class SystemTest {

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

    // --- GET /api/v1/system/health ---

    @Test
    void health_returnsUp() throws Exception {
        var resp = admin.getNoAuth("/api/v1/system/health");
        assertEquals(200, resp.status());
        assertEquals("UP", resp.json().get("status").asText());
    }

    // --- GET /api/v1/system/version ---

    @Test
    void version_returnsVersionInfo() throws Exception {
        // /api/v1/system/version is public — no auth needed
        var resp = admin.get("/api/v1/system/version");
        assertEquals(200, resp.status(), "Response: " + resp.body());
        var json = resp.json();
        assertNotNull(json.get("version"), "Response body: " + resp.body());
    }

    // --- GET /api/v1/system/settings ---

    @Test
    void settings_requiresAuth_returns401() throws Exception {
        var resp = admin.getNoAuth("/api/v1/system/settings");
        assertEquals(401, resp.status());
    }

    @Test
    void settings_withAuth_returnsSettings() throws Exception {
        var resp = admin.get("/api/v1/system/settings");
        assertEquals(200, resp.status());
        assertNotNull(resp.json().get("nodeCount"));
        assertNotNull(resp.json().get("instanceCount"));
        assertNotNull(resp.json().get("playerCount"));
    }

    // --- GET /api/v1/overview ---

    @Test
    void overview_returnsCounts() throws Exception {
        var resp = admin.get("/api/v1/overview");
        assertEquals(200, resp.status());
        assertTrue(resp.json().has("nodeCount"));
        assertTrue(resp.json().has("instanceCount"));
        assertTrue(resp.json().has("playerCount"));
        assertTrue(resp.json().has("groupCount"));
    }

    // --- GET /api/v1/metrics/summary ---

    @Test
    void metricsSummary_returnsCounts() throws Exception {
        var resp = admin.get("/api/v1/metrics/summary");
        assertEquals(200, resp.status());
        assertTrue(resp.json().has("nodes"));
        assertTrue(resp.json().has("instances"));
        assertTrue(resp.json().has("players"));
        assertTrue(resp.json().has("groups"));
        assertTrue(resp.json().has("crashes"));
    }
}
