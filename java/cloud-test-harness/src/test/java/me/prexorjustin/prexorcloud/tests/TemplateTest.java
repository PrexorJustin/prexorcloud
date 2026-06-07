package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/templates/* endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TemplateTest {

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

    // --- GET /api/v1/templates ---

    @Test
    @Order(1)
    void listTemplates_returnsBaseTemplates() throws Exception {
        var resp = admin.get("/api/v1/templates");
        assertEquals(200, resp.status());
        // Base templates should exist from controller startup
        var templates = resp.asList();
        assertNotNull(templates);
    }

    // --- POST /api/v1/templates ---

    @Test
    @Order(10)
    void createTemplate_valid_returns201() throws Exception {
        var resp = admin.post(
                "/api/v1/templates",
                Map.of(
                        "name", "test-template",
                        "description", "A test template",
                        "platform", "PAPER"));
        assertEquals(201, resp.status());
        assertEquals("test-template", resp.json().get("name").asText());
    }

    // --- GET /api/v1/templates/{name} ---

    @Test
    @Order(20)
    void getTemplate_existing_returnsTemplate() throws Exception {
        var resp = admin.get("/api/v1/templates/test-template");
        assertEquals(200, resp.status());
        assertEquals("test-template", resp.json().get("name").asText());
        assertEquals("A test template", resp.json().get("description").asText());
    }

    @Test
    @Order(21)
    void getTemplate_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/templates/nonexistent");
        assertEquals(404, resp.status());
    }

    // --- PATCH /api/v1/templates/{name} ---

    @Test
    @Order(30)
    void updateTemplate_changeDescription_works() throws Exception {
        var resp = admin.patch("/api/v1/templates/test-template", Map.of("description", "Updated description"));
        assertEquals(200, resp.status());
        assertEquals("Updated description", resp.json().get("description").asText());
    }

    @Test
    @Order(31)
    void updateTemplate_notFound_returns404() throws Exception {
        var resp = admin.patch("/api/v1/templates/nonexistent", Map.of("description", "x"));
        assertEquals(404, resp.status());
    }

    // --- Template Files ---

    @Test
    @Order(40)
    void createDirectory_valid_works() throws Exception {
        var resp = admin.postEmpty("/api/v1/templates/test-template/files/mkdir?path=plugins");
        assertEquals(200, resp.status());
    }

    @Test
    @Order(41)
    void createDirectory_missingPath_returns400() throws Exception {
        var resp = admin.postEmpty("/api/v1/templates/test-template/files/mkdir");
        assertEquals(400, resp.status());
    }

    @Test
    @Order(42)
    void createDirectory_pathTraversal_returns400() throws Exception {
        var resp = admin.postEmpty("/api/v1/templates/test-template/files/mkdir?path=../../etc");
        assertEquals(400, resp.status());
    }

    @Test
    @Order(43)
    void saveFileContent_valid_works() throws Exception {
        var resp = admin.put(
                "/api/v1/templates/test-template/files/content?path=server.properties",
                Map.of("content", "server-port=25565\nmotd=Test Server\n"));
        assertEquals(200, resp.status());
    }

    @Test
    @Order(44)
    void readFileContent_existing_returnsContent() throws Exception {
        var resp = admin.get("/api/v1/templates/test-template/files/content?path=server.properties");
        assertEquals(200, resp.status());
        assertTrue(resp.body().contains("server-port=25565"));
    }

    @Test
    @Order(45)
    void readFileContent_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/templates/test-template/files/content?path=nonexistent.txt");
        assertEquals(404, resp.status());
    }

    @Test
    @Order(46)
    void readFileContent_missingPath_returns400() throws Exception {
        var resp = admin.get("/api/v1/templates/test-template/files/content");
        assertEquals(400, resp.status());
    }

    @Test
    @Order(47)
    void listFiles_returnsFiles() throws Exception {
        var resp = admin.get("/api/v1/templates/test-template/files");
        assertEquals(200, resp.status());
        var files = resp.asList();
        assertTrue(files.stream().anyMatch(f -> "server.properties".equals(f.get("name"))));
    }

    @Test
    @Order(48)
    @Disabled(
            "Template files resolve to project dir instead of temp dir — needs controller refactor for absolute paths")
    void renameFile_valid_works() throws Exception {
        // Create a file to rename (unique name to avoid conflicts)
        admin.put("/api/v1/templates/test-template/files/content?path=rename-source.txt", Map.of("content", "test"));

        var resp = admin.post(
                "/api/v1/templates/test-template/files/rename",
                Map.of(
                        "from", "rename-source.txt",
                        "to", "rename-dest.txt"));
        assertEquals(200, resp.status(), "Rename failed: " + resp.body());

        // Old name should not exist
        var oldResp = admin.get("/api/v1/templates/test-template/files/content?path=rename-source.txt");
        assertEquals(404, oldResp.status());

        // New name should exist
        var newResp = admin.get("/api/v1/templates/test-template/files/content?path=rename-dest.txt");
        assertEquals(200, newResp.status());
    }

    @Test
    @Order(49)
    void renameFile_destinationExists_returns409() throws Exception {
        admin.put("/api/v1/templates/test-template/files/content?path=file-a.txt", Map.of("content", "a"));
        admin.put("/api/v1/templates/test-template/files/content?path=file-b.txt", Map.of("content", "b"));

        var resp = admin.post(
                "/api/v1/templates/test-template/files/rename",
                Map.of(
                        "from", "file-a.txt",
                        "to", "file-b.txt"));
        assertEquals(409, resp.status());
    }

    @Test
    @Order(50)
    void deleteFile_existing_returns204() throws Exception {
        admin.put("/api/v1/templates/test-template/files/content?path=to-delete.txt", Map.of("content", "delete me"));

        var resp = admin.delete("/api/v1/templates/test-template/files?path=to-delete.txt");
        assertEquals(204, resp.status());
    }

    @Test
    @Order(51)
    void deleteFile_notFound_returns404() throws Exception {
        var resp = admin.delete("/api/v1/templates/test-template/files?path=nope.txt");
        assertEquals(404, resp.status());
    }

    @Test
    @Order(52)
    void deleteFile_missingPath_returns400() throws Exception {
        var resp = admin.delete("/api/v1/templates/test-template/files");
        assertEquals(400, resp.status());
    }

    // --- Template Variables ---

    @Test
    @Order(60)
    void variables_saveAndRetrieve_works() throws Exception {
        var saveResp = admin.put(
                "/api/v1/templates/test-template/variables",
                List.of(
                        Map.of("key", "SERVER_NAME", "value", "Test", "description", "The server name"),
                        Map.of("key", "MAX_PLAYERS", "value", "50", "description", "Max players")));
        assertEquals(200, saveResp.status());

        var getResp = admin.get("/api/v1/templates/test-template/variables");
        assertEquals(200, getResp.status());
    }

    @Test
    @Order(61)
    void variables_notFoundTemplate_returns404() throws Exception {
        var resp = admin.get("/api/v1/templates/nonexistent/variables");
        assertEquals(404, resp.status());
    }

    // --- Template Search ---

    @Test
    @Order(70)
    void search_findsContent_returnsMatches() throws Exception {
        var resp = admin.get("/api/v1/templates/test-template/search?q=server-port");
        assertEquals(200, resp.status());
        var matches = resp.asList();
        assertFalse(matches.isEmpty());
        assertEquals("server.properties", matches.get(0).get("path"));
    }

    @Test
    @Order(71)
    void search_missingQuery_returns400() throws Exception {
        var resp = admin.get("/api/v1/templates/test-template/search");
        assertEquals(400, resp.status());
    }

    @Test
    @Order(72)
    void search_noMatches_returnsEmptyList() throws Exception {
        var resp = admin.get("/api/v1/templates/test-template/search?q=xyznonexistent");
        assertEquals(200, resp.status());
        assertTrue(resp.asList().isEmpty());
    }

    // --- Template Inheritance ---

    @Test
    @Order(75)
    void inheritance_returnsChain() throws Exception {
        var resp = admin.get("/api/v1/templates/test-template/inheritance");
        assertEquals(200, resp.status());
        var chain = resp.asList();
        assertFalse(chain.isEmpty());
    }

    // --- Rehash ---

    @Test
    @Order(80)
    void rehash_works() throws Exception {
        var resp = admin.postEmpty("/api/v1/templates/test-template/rehash");
        assertEquals(200, resp.status());
    }

    // --- Template Versions ---

    @Test
    @Order(85)
    void versions_listVersions() throws Exception {
        var resp = admin.get("/api/v1/templates/test-template/versions");
        assertEquals(200, resp.status());
    }

    // --- Delete Template ---

    @Test
    @Order(99)
    void deleteTemplate_existing_returns204() throws Exception {
        var resp = admin.delete("/api/v1/templates/test-template");
        assertEquals(204, resp.status());
    }

    @Test
    @Order(100)
    void deleteTemplate_notFound_returns404() throws Exception {
        var resp = admin.delete("/api/v1/templates/nonexistent");
        assertEquals(404, resp.status());
    }
}
