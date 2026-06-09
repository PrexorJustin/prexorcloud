package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/services/* endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceTest {

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

    // --- GET /api/v1/services ---

    @Test
    @Order(1)
    void listInstances_empty_returnsEmptyList() throws Exception {
        var resp = admin.get("/api/v1/services");
        assertEquals(200, resp.status());
        assertTrue(resp.asList().isEmpty());
    }

    @Test
    @Order(2)
    void listInstances_withFilters_returnsFiltered() throws Exception {
        var resp = admin.get("/api/v1/services?group=lobby&state=RUNNING&node=test-node-1");
        assertEquals(200, resp.status());
        // Should return empty or filtered results, but not error
        assertNotNull(resp.asList());
    }

    // --- GET /api/v1/services/{id} ---

    @Test
    @Order(10)
    void getInstance_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/services/nonexistent-1");
        assertEquals(404, resp.status());
    }

    // --- POST /api/v1/services/{id}/stop ---

    @Test
    @Order(20)
    void stopInstance_notFound_returns404() throws Exception {
        var resp = admin.postEmpty("/api/v1/services/nonexistent-1/stop");
        assertEquals(404, resp.status());
    }

    // --- POST /api/v1/services/{id}/force-stop ---

    @Test
    @Order(21)
    void forceStopInstance_notFound_returns404() throws Exception {
        var resp = admin.postEmpty("/api/v1/services/nonexistent-1/force-stop");
        assertEquals(404, resp.status());
    }

    // --- GET /api/v1/services/{id}/metrics ---

    @Test
    @Order(30)
    void instanceMetrics_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/services/nonexistent-1/metrics");
        assertEquals(404, resp.status());
    }

    // --- GET /api/v1/services/{id}/proxy-metrics ---

    @Test
    @Order(31)
    void instanceProxyMetrics_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/services/nonexistent-1/proxy-metrics");
        assertEquals(404, resp.status());
    }

    // --- DELETE /api/v1/services/{id} ---

    @Test
    @Order(40)
    void deleteInstance_notFound_returns404() throws Exception {
        var resp = admin.delete("/api/v1/services/nonexistent-1");
        assertEquals(404, resp.status());
    }

    // --- POST /api/v1/services/{id}/command ---

    @Test
    @Order(50)
    void sendCommand_notFound_returns404() throws Exception {
        var resp = admin.post("/api/v1/services/nonexistent-1/command", Map.of("command", "say hello"));
        assertEquals(404, resp.status());
    }
}
