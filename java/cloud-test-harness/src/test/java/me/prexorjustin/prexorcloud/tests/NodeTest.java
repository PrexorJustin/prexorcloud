package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/nodes/* endpoints.
 * Requires a running daemon to test connected node features.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NodeTest {

    static TestCluster cluster;
    static RestClient admin;

    @BeforeAll
    static void setup() throws Exception {
        cluster = TestCluster.start(1); // Start with 1 daemon
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    // --- GET /api/v1/nodes ---

    @Test
    @Order(1)
    void listNodes_showsConnectedDaemon() throws Exception {
        var resp = admin.get("/api/v1/nodes");
        assertEquals(200, resp.status());
        var nodes = resp.asList();
        assertTrue(nodes.size() >= 1);

        var connected =
                nodes.stream().filter(n -> "CONNECTED".equals(n.get("type"))).findFirst();
        assertTrue(connected.isPresent());
        assertEquals("test-node-1", connected.get().get("id"));
    }

    // --- GET /api/v1/nodes/{id} ---

    @Test
    @Order(10)
    void getNode_connected_returnsFullInfo() throws Exception {
        var resp = admin.get("/api/v1/nodes/test-node-1");
        assertEquals(200, resp.status());
        assertEquals("CONNECTED", resp.json().get("type").asText());
        assertTrue(resp.json().has("cpuUsage"));
        assertTrue(resp.json().has("totalMemoryMb"));
        assertTrue(resp.json().has("connectedSince"));
    }

    @Test
    @Order(11)
    void getNode_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/nodes/nonexistent");
        assertEquals(404, resp.status());
    }

    // --- POST /api/v1/nodes/{id}/cordon ---

    @Test
    @Order(20)
    void cordonNode_works() throws Exception {
        var resp = admin.postEmpty("/api/v1/nodes/test-node-1/cordon");
        assertEquals(200, resp.status());
        assertEquals("CORDONED", resp.json().get("status").asText());
    }

    // --- POST /api/v1/nodes/{id}/uncordon ---

    @Test
    @Order(21)
    void uncordonNode_works() throws Exception {
        var resp = admin.postEmpty("/api/v1/nodes/test-node-1/uncordon");
        assertEquals(200, resp.status());
        assertEquals("ONLINE", resp.json().get("status").asText());
    }

    @Test
    @Order(22)
    void cordonNode_notFound_returns404() throws Exception {
        var resp = admin.postEmpty("/api/v1/nodes/nonexistent/cordon");
        assertEquals(404, resp.status());
    }

    // --- POST /api/v1/nodes/{id}/drain ---

    @Test
    @Order(30)
    void drainNode_works() throws Exception {
        var resp = admin.postEmpty("/api/v1/nodes/test-node-1/drain?shutdown=false&timeout=10");
        assertEquals(200, resp.status());
        assertEquals("DRAINING", resp.json().get("status").asText());
    }

    // --- POST /api/v1/nodes/{id}/undrain ---

    @Test
    @Order(31)
    void undrainNode_works() throws Exception {
        var resp = admin.postEmpty("/api/v1/nodes/test-node-1/undrain");
        assertEquals(200, resp.status());
        assertEquals("ONLINE", resp.json().get("status").asText());
    }

    @Test
    @Order(32)
    void drainNode_notFound_returns404() throws Exception {
        var resp = admin.postEmpty("/api/v1/nodes/nonexistent/drain");
        assertEquals(404, resp.status());
    }

    // --- GET /api/v1/nodes/{id}/cache ---

    @Test
    @Order(40)
    void nodeCache_returnsData() throws Exception {
        var resp = admin.get("/api/v1/nodes/test-node-1/cache");
        assertEquals(200, resp.status());
        assertTrue(resp.json().has("templates") || resp.json().has("totalSizeBytes"));
    }

    // --- POST /api/v1/nodes/{id}/cache/refresh ---

    @Test
    @Order(41)
    void nodeCacheRefresh_returns202() throws Exception {
        var resp = admin.postEmpty("/api/v1/nodes/test-node-1/cache/refresh");
        assertEquals(202, resp.status());
    }

    @Test
    @Order(42)
    void nodeCacheRefresh_noSession_returns404() throws Exception {
        var resp = admin.postEmpty("/api/v1/nodes/nonexistent/cache/refresh");
        assertEquals(404, resp.status());
    }

    // --- POST /api/v1/nodes/{id}/cache/warm ---

    @Test
    @Order(43)
    void nodeCacheWarm_returns202() throws Exception {
        var resp = admin.postEmpty("/api/v1/nodes/test-node-1/cache/warm");
        assertEquals(202, resp.status());
    }

    // --- DELETE /api/v1/nodes/{id} (can't delete connected) ---

    @Test
    @Order(50)
    void deleteNode_connected_returns409() throws Exception {
        var resp = admin.delete("/api/v1/nodes/test-node-1");
        assertEquals(409, resp.status());
    }

    // --- Pending nodes (from tokens) ---

    @Test
    @Order(60)
    void pendingNode_showsInNodeList() throws Exception {
        // Create a join token for a node that won't connect
        admin.post("/api/v1/admin/tokens", Map.of("nodeId", "pending-node-1", "ttlSeconds", 3600));

        var resp = admin.get("/api/v1/nodes");
        assertEquals(200, resp.status());
        var nodes = resp.asList();
        var pending = nodes.stream()
                .filter(n -> "PENDING".equals(n.get("type")))
                .filter(n -> "pending-node-1".equals(n.get("id")))
                .findFirst();
        assertTrue(pending.isPresent());
    }
}
