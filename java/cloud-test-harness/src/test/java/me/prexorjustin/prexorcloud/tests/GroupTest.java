package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/groups/* endpoints.
 * Uses "gt-" prefix for group names to avoid conflicts with default groups.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GroupTest {

    static TestCluster cluster;
    static RestClient admin;

    @BeforeAll
    static void setup() throws Exception {
        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

        // Clean up any pre-existing test groups from default configs
        for (String name : List.of("gt-lby", "gt-survival", "gt-hub", "gt-base-game", "gt-minigame")) {
            try {
                admin.delete("/api/v1/groups/" + name);
            } catch (Exception _) {
            }
        }
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    // --- POST /api/v1/groups ---

    @Test
    @Order(1)
    void createGroup_minimal_returns201() throws Exception {
        var resp = admin.post(
                "/api/v1/groups",
                Map.of(
                        "name",
                        "gt-lby",
                        "platform",
                        "PAPER",
                        "platformVersion",
                        "1.21.1",
                        "minInstances",
                        1,
                        "maxInstances",
                        5,
                        "maxPlayers",
                        50));
        assertEquals(201, resp.status(), "Group creation failed: " + resp.body());
        assertEquals("gt-lby", resp.json().get("name").asText());
        assertEquals("PAPER", resp.json().get("platform").asText());
    }

    @Test
    @Order(2)
    void createGroup_withAllFields_returns201() throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("name", "gt-survival");
        body.put("platform", "PAPER");
        body.put("platformVersion", "1.21.1");
        body.put("jarFile", "server.jar");
        body.put("templates", List.of());
        body.put("scalingMode", "DYNAMIC");
        body.put("minInstances", 2);
        body.put("maxInstances", 10);
        body.put("maxPlayers", 100);
        body.put("scaleUpThreshold", 0.8);
        body.put("scaleDownAfterSeconds", 300);
        body.put("scaleCooldownSeconds", 60);
        body.put("portRangeStart", 30000);
        body.put("portRangeEnd", 31000);
        body.put("startupTimeoutSeconds", 120);
        body.put("shutdownGraceSeconds", 30);
        body.put("maxLifetimeSeconds", 3600);
        body.put("memoryMb", 1024);
        body.put("jvmArgs", List.of("-Xms512m"));
        body.put("env", Map.of("JAVA_OPTS", "-Dtest=true"));
        body.put("maintenance", false);
        body.put("defaultGroup", false);
        body.put("updateStrategy", "ROLLING");
        var resp = admin.post("/api/v1/groups", body);
        assertEquals(201, resp.status(), "Failed: " + resp.body());
        assertEquals("gt-survival", resp.json().get("name").asText());
    }

    @Test
    @Order(3)
    void createGroup_static_returns201() throws Exception {
        var resp = admin.post(
                "/api/v1/groups",
                Map.of(
                        "name",
                        "gt-hub",
                        "platform",
                        "PAPER",
                        "platformVersion",
                        "1.21.1",
                        "static",
                        true,
                        "staticInstanceNames",
                        List.of("gt-hub-main", "gt-hub-vip"),
                        "minInstances",
                        0,
                        "maxInstances",
                        0,
                        "maxPlayers",
                        200,
                        "defaultGroup",
                        true));
        assertEquals(201, resp.status(), "Failed: " + resp.body());
        assertTrue(resp.json().get("static").asBoolean());
    }

    // --- GET /api/v1/groups ---

    @Test
    @Order(10)
    void listGroups_returnsCreatedGroups() throws Exception {
        var resp = admin.get("/api/v1/groups");
        assertEquals(200, resp.status());
        var groups = resp.asList();
        assertTrue(groups.stream().anyMatch(g -> "gt-lby".equals(g.get("name"))));
        assertTrue(groups.stream().anyMatch(g -> "gt-survival".equals(g.get("name"))));
    }

    // --- GET /api/v1/groups/{name} ---

    @Test
    @Order(20)
    void getGroup_existing_returnsGroup() throws Exception {
        var resp = admin.get("/api/v1/groups/gt-lby");
        assertEquals(200, resp.status());
        assertEquals("gt-lby", resp.json().get("name").asText());
        assertTrue(resp.json().has("runningInstances"));
        assertTrue(resp.json().has("totalPlayers"));
    }

    @Test
    @Order(21)
    void getGroup_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/groups/nonexistent");
        assertEquals(404, resp.status());
    }

    // --- GET /api/v1/groups/{name}/resolved ---

    @Test
    @Order(25)
    void getGroupResolved_returnsResolvedConfig() throws Exception {
        var resp = admin.get("/api/v1/groups/gt-lby/resolved");
        assertEquals(200, resp.status());
        assertEquals("gt-lby", resp.json().get("name").asText());
    }

    @Test
    @Order(26)
    void getGroupResolved_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/groups/nonexistent/resolved");
        assertEquals(404, resp.status());
    }

    // --- PATCH /api/v1/groups/{name} ---

    @Test
    @Order(30)
    void updateGroup_partialUpdate_mergesCorrectly() throws Exception {
        var resp = admin.patch("/api/v1/groups/gt-lby", Map.of("maxPlayers", 100, "maintenance", true));
        assertEquals(200, resp.status());
        assertEquals(100, resp.json().get("maxPlayers").asInt());
        assertTrue(resp.json().get("maintenance").asBoolean());
        assertEquals("PAPER", resp.json().get("platform").asText());
    }

    @Test
    @Order(31)
    void updateGroup_envMerge_works() throws Exception {
        var resp = admin.patch("/api/v1/groups/gt-survival", Map.of("env", Map.of("NEW_VAR", "new_value")));
        assertEquals(200, resp.status());
        assertNotNull(resp.json().get("env"));
    }

    @Test
    @Order(32)
    void updateGroup_notFound_returns404() throws Exception {
        var resp = admin.patch("/api/v1/groups/nonexistent", Map.of("maxPlayers", 100));
        assertEquals(404, resp.status());
    }

    // --- POST /api/v1/groups/{name}/start ---

    @Test
    @Order(40)
    void startGroup_notFound_returns404() throws Exception {
        var resp = admin.postEmpty("/api/v1/groups/nonexistent/start");
        assertEquals(404, resp.status());
    }

    // --- Parent inheritance ---

    @Test
    @Order(50)
    void parentInheritance_childInheritsFromParent() throws Exception {
        admin.post(
                "/api/v1/groups",
                Map.of(
                        "name", "gt-base-game",
                        "platform", "PAPER",
                        "platformVersion", "1.21.1",
                        "memoryMb", 2048,
                        "minInstances", 1,
                        "maxInstances", 10,
                        "maxPlayers", 50));

        admin.post(
                "/api/v1/groups",
                Map.of(
                        "name", "gt-minigame",
                        "parent", "gt-base-game",
                        "minInstances", 3,
                        "maxInstances", 20,
                        "maxPlayers", 30));

        var resp = admin.get("/api/v1/groups/gt-minigame/resolved");
        assertEquals(200, resp.status());
        assertEquals("PAPER", resp.json().get("platform").asText());

        admin.delete("/api/v1/groups/gt-minigame");
        admin.delete("/api/v1/groups/gt-base-game");
    }

    // --- Deployment sub-routes ---

    @Test
    @Order(60)
    void deployments_listEmpty_returnsEmptyList() throws Exception {
        var resp = admin.get("/api/v1/groups/gt-lby/deployments");
        assertEquals(200, resp.status());
        assertTrue(resp.asList().isEmpty());
    }

    @Test
    @Order(61)
    void deployments_notFoundGroup_returns404() throws Exception {
        var resp = admin.get("/api/v1/groups/nonexistent/deployments");
        assertEquals(404, resp.status());
    }

    // --- DELETE /api/v1/groups/{name} ---

    @Test
    @Order(90)
    void deleteGroup_existing_returns204() throws Exception {
        var resp = admin.delete("/api/v1/groups/gt-survival");
        assertEquals(204, resp.status());
    }

    @Test
    @Order(91)
    void deleteGroup_notFound_returns404() throws Exception {
        var resp = admin.delete("/api/v1/groups/nonexistent");
        assertEquals(404, resp.status());
    }
}
