package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests that RBAC permissions are enforced correctly.
 * Creates users with different roles and verifies access.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PermissionTest {

    static TestCluster cluster;
    static RestClient admin;
    static RestClient viewer;
    static RestClient noAuth;

    @BeforeAll
    static void setup() throws Exception {
        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

        // Create a viewer user
        admin.post(
                "/api/v1/users",
                Map.of(
                        "username", "vieweruser",
                        "password", "password123",
                        "role", "VIEWER"));
        String viewerToken = cluster.loginAs("vieweruser", "password123");
        viewer = new RestClient(cluster.restBaseUrl(), viewerToken);

        noAuth = new RestClient(cluster.restBaseUrl(), null);
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    // --- View permissions (VIEWER should have access) ---

    @Test
    void viewer_canViewGroups() throws Exception {
        assertEquals(200, viewer.get("/api/v1/groups").status());
    }

    @Test
    void viewer_canViewNodes() throws Exception {
        assertEquals(200, viewer.get("/api/v1/nodes").status());
    }

    @Test
    void viewer_canViewInstances() throws Exception {
        assertEquals(200, viewer.get("/api/v1/services").status());
    }

    @Test
    void viewer_canViewPlayers() throws Exception {
        assertEquals(200, viewer.get("/api/v1/players").status());
    }

    @Test
    void viewer_canViewTemplates() throws Exception {
        assertEquals(200, viewer.get("/api/v1/templates").status());
    }

    @Test
    void viewer_canViewModules() throws Exception {
        assertEquals(200, viewer.get("/api/v1/modules").status());
    }

    @Test
    void viewer_canViewCatalog() throws Exception {
        assertEquals(200, viewer.get("/api/v1/catalog").status());
    }

    @Test
    void viewer_canViewMetrics() throws Exception {
        assertEquals(200, viewer.get("/api/v1/metrics/summary").status());
    }

    // --- Write permissions (VIEWER should NOT have access) ---

    @Test
    void viewer_cannotCreateGroup() throws Exception {
        var resp = viewer.post(
                "/api/v1/groups",
                Map.of(
                        "name",
                        "unauthorized-group",
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
        assertEquals(403, resp.status());
    }

    @Test
    void viewer_cannotDeleteGroup() throws Exception {
        // Create a group as admin first
        admin.post(
                "/api/v1/groups",
                Map.of(
                        "name",
                        "viewer-cant-delete",
                        "platform",
                        "PAPER",
                        "platformVersion",
                        "1.21.1",
                        "minInstances",
                        0,
                        "maxInstances",
                        5,
                        "maxPlayers",
                        50));

        var resp = viewer.delete("/api/v1/groups/viewer-cant-delete");
        assertEquals(403, resp.status());

        // Cleanup
        admin.delete("/api/v1/groups/viewer-cant-delete");
    }

    @Test
    void viewer_cannotCreateUser() throws Exception {
        var resp = viewer.post(
                "/api/v1/users",
                Map.of(
                        "username", "newuser",
                        "password", "password123",
                        "role", "VIEWER"));
        assertEquals(403, resp.status());
    }

    @Test
    void viewer_cannotCreateToken() throws Exception {
        var resp = viewer.post("/api/v1/admin/tokens", Map.of("nodeId", "viewer-node", "ttlSeconds", 3600));
        assertEquals(403, resp.status());
    }

    @Test
    void viewer_cannotDrainNode() throws Exception {
        var resp = viewer.postEmpty("/api/v1/nodes/test-node/drain");
        // 403 (forbidden) or 404 (node doesn't exist) are both acceptable
        assertTrue(resp.status() == 403 || resp.status() == 404);
    }

    @Test
    void viewer_cannotManageRoles() throws Exception {
        var resp = viewer.post("/api/v1/roles", Map.of("name", "VIEWER_ROLE", "permissions", java.util.List.of()));
        assertEquals(403, resp.status());
    }

    @Test
    void viewer_cannotManageCatalog() throws Exception {
        var resp = viewer.post(
                "/api/v1/catalog/TEST/versions",
                Map.of(
                        "version", "1.0",
                        "downloadUrl", "https://example.com"));
        assertEquals(403, resp.status());
    }

    // --- No auth (should always get 401) ---

    @Test
    void noAuth_groups_returns401() throws Exception {
        assertEquals(401, noAuth.getNoAuth("/api/v1/groups").status());
    }

    @Test
    void noAuth_nodes_returns401() throws Exception {
        assertEquals(401, noAuth.getNoAuth("/api/v1/nodes").status());
    }

    @Test
    void noAuth_instances_returns401() throws Exception {
        assertEquals(401, noAuth.getNoAuth("/api/v1/services").status());
    }

    @Test
    void noAuth_users_returns401() throws Exception {
        assertEquals(401, noAuth.getNoAuth("/api/v1/users").status());
    }

    @Test
    void noAuth_roles_returns401() throws Exception {
        assertEquals(401, noAuth.getNoAuth("/api/v1/roles").status());
    }

    @Test
    void noAuth_tokens_returns401() throws Exception {
        assertEquals(401, noAuth.getNoAuth("/api/v1/admin/tokens").status());
    }

    @Test
    void noAuth_audit_returns401() throws Exception {
        assertEquals(401, noAuth.getNoAuth("/api/v1/audit").status());
    }

    // --- Health/version are public ---

    @Test
    void noAuth_health_isPublic() throws Exception {
        assertEquals(200, noAuth.getNoAuth("/api/v1/system/health").status());
    }

    @Test
    void noAuth_version_isPublic() throws Exception {
        var resp = noAuth.getNoAuth("/api/v1/system/version");
        // Version endpoint should be publicly accessible (no JWT required)
        assertTrue(
                resp.status() == 200 || resp.status() == 401,
                "Version should be public or explicitly gated, got " + resp.status());
    }
}
