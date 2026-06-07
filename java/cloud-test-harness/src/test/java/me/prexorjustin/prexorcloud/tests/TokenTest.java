package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/admin/tokens/* endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TokenTest {

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

    // --- POST /api/v1/admin/tokens ---

    @Test
    @Order(1)
    void createToken_valid_returns201() throws Exception {
        var resp = admin.post("/api/v1/admin/tokens", Map.of("nodeId", "new-node-1", "ttlSeconds", 3600));
        assertEquals(201, resp.status());
        assertNotNull(resp.json().get("tokenId").asText());
        assertNotNull(resp.json().get("joinToken").asText());
        assertNotNull(resp.json().get("expiresAt").asText());
    }

    @Test
    @Order(2)
    void createToken_defaultTtl_works() throws Exception {
        var resp = admin.post("/api/v1/admin/tokens", Map.of("nodeId", "new-node-2", "ttlSeconds", 0));
        assertEquals(201, resp.status());
    }

    @Test
    @Order(3)
    void createToken_pendingTokenExists_returns409() throws Exception {
        var resp = admin.post("/api/v1/admin/tokens", Map.of("nodeId", "new-node-1", "ttlSeconds", 3600));
        assertEquals(409, resp.status());
    }

    // --- GET /api/v1/admin/tokens ---

    @Test
    @Order(10)
    void listTokens_returnsTokens() throws Exception {
        var resp = admin.get("/api/v1/admin/tokens");
        assertEquals(200, resp.status());
        var tokens = resp.asList();
        assertTrue(tokens.size() >= 2); // The ones we created
    }

    // --- DELETE /api/v1/admin/tokens/{id} ---

    @Test
    @Order(20)
    void revokeToken_existing_returns204() throws Exception {
        // Get the token ID of one of our tokens
        var listResp = admin.get("/api/v1/admin/tokens");
        var tokens = listResp.asList();
        assertFalse(tokens.isEmpty());
        String tokenId = (String) tokens.get(0).get("tokenId");

        var resp = admin.delete("/api/v1/admin/tokens/" + tokenId);
        assertEquals(204, resp.status());
    }
}
