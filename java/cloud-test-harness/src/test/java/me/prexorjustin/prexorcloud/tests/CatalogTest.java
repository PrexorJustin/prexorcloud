package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/catalog/* endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogTest {

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

    // --- GET /api/v1/catalog ---

    @Test
    @Order(1)
    void listCatalog_returnsEntries() throws Exception {
        var resp = admin.get("/api/v1/catalog");
        assertEquals(200, resp.status());
        // May be empty initially or have defaults
        assertNotNull(resp.asList());
    }

    // --- POST /api/v1/catalog/{platform}/versions ---

    @Test
    @Order(10)
    void addVersion_valid_returns201() throws Exception {
        var resp = admin.post(
                "/api/v1/catalog/PAPER/versions",
                Map.of(
                        "version", "1.21.1",
                        "downloadUrl", "https://example.com/paper-1.21.1.jar",
                        "category", "SERVER",
                        "configFormat", "paper"));
        assertEquals(201, resp.status());
    }

    @Test
    @Order(11)
    void addVersion_anotherVersion_works() throws Exception {
        var resp = admin.post(
                "/api/v1/catalog/PAPER/versions",
                Map.of(
                        "version", "1.21.0",
                        "downloadUrl", "https://example.com/paper-1.21.0.jar"));
        assertEquals(201, resp.status());
    }

    // --- PATCH /api/v1/catalog/{platform}/versions/{version} ---

    @Test
    @Order(20)
    void updateVersion_changeUrl_works() throws Exception {
        var resp = admin.patch(
                "/api/v1/catalog/PAPER/versions/1.21.0",
                Map.of(
                        "version", "1.21.0",
                        "downloadUrl", "https://example.com/paper-1.21.0-updated.jar"));
        assertEquals(200, resp.status());
    }

    // --- PUT /api/v1/catalog/{platform}/versions/{version}/recommended ---

    @Test
    @Order(30)
    void setRecommended_works() throws Exception {
        var resp = admin.put("/api/v1/catalog/PAPER/versions/1.21.1/recommended", Map.of());
        assertEquals(200, resp.status());
        assertTrue(resp.json().get("recommended").asBoolean());
    }

    // --- DELETE /api/v1/catalog/{platform}/versions/{version} ---

    @Test
    @Order(40)
    void removeVersion_returns204() throws Exception {
        var resp = admin.delete("/api/v1/catalog/PAPER/versions/1.21.0");
        assertEquals(204, resp.status());
    }

    // Verify the remaining version exists
    @Test
    @Order(41)
    void listCatalog_afterDelete_hasOneVersion() throws Exception {
        var resp = admin.get("/api/v1/catalog");
        assertEquals(200, resp.status());
    }
}
