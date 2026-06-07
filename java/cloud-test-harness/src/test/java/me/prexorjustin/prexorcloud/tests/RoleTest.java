package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/roles/* endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoleTest {

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

    // --- GET /api/v1/roles ---

    @Test
    @Order(1)
    void listRoles_returnsBuiltInRoles() throws Exception {
        var resp = admin.get("/api/v1/roles");
        assertEquals(200, resp.status());
        var roles = resp.asList();
        assertTrue(roles.size() >= 3); // ADMIN, OPERATOR, VIEWER
        assertTrue(roles.stream().anyMatch(r -> "ADMIN".equals(r.get("name"))));
        assertTrue(roles.stream().anyMatch(r -> "OPERATOR".equals(r.get("name"))));
        assertTrue(roles.stream().anyMatch(r -> "VIEWER".equals(r.get("name"))));
    }

    // --- POST /api/v1/roles ---

    @Test
    @Order(10)
    void createRole_valid_returns201() throws Exception {
        var resp = admin.post(
                "/api/v1/roles",
                Map.of("name", "CUSTOM_ROLE", "permissions", List.of("GROUPS_VIEW", "INSTANCES_VIEW")));
        assertEquals(201, resp.status());
        assertEquals("CUSTOM_ROLE", resp.json().get("name").asText());
        assertFalse(resp.json().get("builtIn").asBoolean());
    }

    @Test
    @Order(11)
    void createRole_duplicate_returnsError() throws Exception {
        var resp = admin.post("/api/v1/roles", Map.of("name", "CUSTOM_ROLE", "permissions", List.of()));
        assertTrue(resp.status() >= 400);
    }

    @Test
    @Order(12)
    void createRole_overwriteBuiltIn_returnsError() throws Exception {
        var resp = admin.post("/api/v1/roles", Map.of("name", "ADMIN", "permissions", List.of()));
        assertTrue(resp.status() >= 400);
    }

    @Test
    @Order(13)
    void createRole_invalidName_returnsError() throws Exception {
        var resp = admin.post("/api/v1/roles", Map.of("name", "lowercase-bad", "permissions", List.of()));
        assertTrue(resp.status() >= 400);
    }

    // --- GET /api/v1/roles/{name} ---

    @Test
    @Order(20)
    void getRole_existing_returnsRole() throws Exception {
        var resp = admin.get("/api/v1/roles/CUSTOM_ROLE");
        assertEquals(200, resp.status());
        assertEquals("CUSTOM_ROLE", resp.json().get("name").asText());
    }

    @Test
    @Order(21)
    void getRole_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/roles/NONEXISTENT");
        assertEquals(404, resp.status());
    }

    // --- PATCH /api/v1/roles/{name} ---

    @Test
    @Order(30)
    void updateRole_changePermissions_works() throws Exception {
        var resp = admin.patch(
                "/api/v1/roles/CUSTOM_ROLE",
                Map.of("permissions", List.of("GROUPS_VIEW", "INSTANCES_VIEW", "PLAYERS_VIEW")));
        assertEquals(200, resp.status());
    }

    @Test
    @Order(31)
    void updateRole_adminPermissionsImmutable() throws Exception {
        // ADMIN role permissions should stay unchanged even if we try to modify
        var before = admin.get("/api/v1/roles/ADMIN");
        admin.patch("/api/v1/roles/ADMIN", Map.of("permissions", List.of("GROUPS_VIEW")));
        var after = admin.get("/api/v1/roles/ADMIN");
        assertEquals(before.json().get("permissions"), after.json().get("permissions"));
    }

    // --- DELETE /api/v1/roles/{name} ---

    @Test
    @Order(40)
    void deleteRole_builtIn_returns403() throws Exception {
        var resp = admin.delete("/api/v1/roles/ADMIN");
        assertEquals(403, resp.status());
    }

    @Test
    @Order(41)
    void deleteRole_withAssignedUsers_returns409() throws Exception {
        // Create user with CUSTOM_ROLE
        admin.post(
                "/api/v1/users",
                Map.of(
                        "username", "roleuser",
                        "password", "password123",
                        "role", "CUSTOM_ROLE"));

        var resp = admin.delete("/api/v1/roles/CUSTOM_ROLE");
        assertEquals(409, resp.status());

        // Cleanup
        admin.delete("/api/v1/users/roleuser");
    }

    @Test
    @Order(42)
    void deleteRole_custom_returns204() throws Exception {
        var resp = admin.delete("/api/v1/roles/CUSTOM_ROLE");
        assertEquals(204, resp.status());

        // Verify deleted
        var getResp = admin.get("/api/v1/roles/CUSTOM_ROLE");
        assertEquals(404, getResp.status());
    }

    @Test
    @Order(43)
    void deleteRole_notFound_returns404() throws Exception {
        var resp = admin.delete("/api/v1/roles/NONEXISTENT");
        assertEquals(404, resp.status());
    }
}
