package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/users/* endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserTest {

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

    // --- GET /api/v1/users ---

    @Test
    @Order(1)
    void listUsers_returnsAtLeastAdmin() throws Exception {
        var resp = admin.get("/api/v1/users");
        assertEquals(200, resp.status());
        var users = resp.asList();
        assertTrue(users.size() >= 1);
        assertTrue(users.stream().anyMatch(u -> "admin".equals(u.get("username"))));
    }

    // --- POST /api/v1/users ---

    @Test
    @Order(10)
    void createUser_valid_returns201() throws Exception {
        var resp = admin.post(
                "/api/v1/users",
                Map.of(
                        "username", "testuser1",
                        "password", "password123",
                        "role", "VIEWER"));
        assertEquals(201, resp.status());
        assertEquals("testuser1", resp.json().get("username").asText());
        assertEquals("VIEWER", resp.json().get("role").asText());
    }

    @Test
    @Order(11)
    void createUser_duplicate_returnsError() throws Exception {
        // testuser1 already exists from previous test
        var resp = admin.post(
                "/api/v1/users",
                Map.of(
                        "username", "testuser1",
                        "password", "password123",
                        "role", "VIEWER"));
        assertTrue(resp.status() >= 400);
    }

    // --- GET /api/v1/users/{username} ---

    @Test
    @Order(20)
    void getUser_existing_returnsUser() throws Exception {
        var resp = admin.get("/api/v1/users/testuser1");
        assertEquals(200, resp.status());
        assertEquals("testuser1", resp.json().get("username").asText());
    }

    @Test
    @Order(21)
    void getUser_notFound_returns404() throws Exception {
        var resp = admin.get("/api/v1/users/nonexistent");
        assertEquals(404, resp.status());
    }

    // --- PATCH /api/v1/users/{username} ---

    @Test
    @Order(30)
    void updateUser_changeRole_returnsUpdated() throws Exception {
        var resp = admin.patch("/api/v1/users/testuser1", Map.of("role", "OPERATOR"));
        assertEquals(200, resp.status());
        assertEquals("OPERATOR", resp.json().get("role").asText());
    }

    @Test
    @Order(31)
    void updateUser_selfUpdateUsername_works() throws Exception {
        // Create a user and login as them
        admin.post(
                "/api/v1/users",
                Map.of(
                        "username", "selfupdater",
                        "password", "password123",
                        "role", "VIEWER"));
        var token = cluster.loginAs("selfupdater", "password123");
        var userClient = new RestClient(cluster.restBaseUrl(), token);

        // Self-update username should work without USERS_UPDATE
        var resp = userClient.patch("/api/v1/users/selfupdater", Map.of("username", "selfrenamed"));
        assertEquals(200, resp.status());

        // Cleanup
        admin.delete("/api/v1/users/selfrenamed");
    }

    // --- DELETE /api/v1/users/{username} ---

    @Test
    @Order(40)
    void deleteUser_existing_returns204() throws Exception {
        var resp = admin.delete("/api/v1/users/testuser1");
        assertEquals(204, resp.status());

        // Verify deleted
        var getResp = admin.get("/api/v1/users/testuser1");
        assertEquals(404, getResp.status());
    }

    // --- User Preferences ---

    @Test
    @Order(50)
    void preferences_getDefault_returnsJson() throws Exception {
        var resp = admin.get("/api/v1/users/admin/preferences");
        assertEquals(200, resp.status());
        assertNotNull(resp.body());
        assertTrue(resp.body().contains("notifications"));
    }

    @Test
    @Order(51)
    void preferences_saveAndRetrieve_works() throws Exception {
        String prefs = "{\"theme\":\"dark\",\"custom\":true}";
        var saveResp = admin.putRaw("/api/v1/users/admin/preferences", "application/json", prefs);
        assertEquals(200, saveResp.status());

        var getResp = admin.get("/api/v1/users/admin/preferences");
        assertEquals(200, getResp.status());
        assertTrue(getResp.body().contains("dark"));
    }

    @Test
    @Order(52)
    void preferences_invalidJson_returns400() throws Exception {
        var resp = admin.putRaw("/api/v1/users/admin/preferences", "application/json", "not-json{{{");
        assertEquals(400, resp.status());
    }

    // --- Minecraft Linking ---

    @Test
    @Order(60)
    void minecraftLink_valid_works() throws Exception {
        var resp = admin.put(
                "/api/v1/users/admin/minecraft",
                Map.of(
                        "uuid", "550e8400-e29b-41d4-a716-446655440000",
                        "name", "TestPlayer"));
        assertEquals(200, resp.status());
        assertEquals("TestPlayer", resp.json().get("minecraftName").asText());
    }

    @Test
    @Order(61)
    void minecraftLink_missingFields_returns400() throws Exception {
        var resp = admin.put("/api/v1/users/admin/minecraft", Map.of("uuid", ""));
        assertEquals(400, resp.status());
    }

    @Test
    @Order(62)
    void minecraftLink_conflictWithOtherUser_returns409() throws Exception {
        // Create another user and link to the same UUID
        admin.post(
                "/api/v1/users",
                Map.of(
                        "username", "conflictuser",
                        "password", "password123",
                        "role", "VIEWER"));

        var resp = admin.put(
                "/api/v1/users/conflictuser/minecraft",
                Map.of(
                        "uuid", "550e8400-e29b-41d4-a716-446655440000",
                        "name", "TestPlayer"));
        assertEquals(409, resp.status());

        // Cleanup
        admin.delete("/api/v1/users/conflictuser");
    }

    @Test
    @Order(63)
    void minecraftUnlink_works() throws Exception {
        var resp = admin.delete("/api/v1/users/admin/minecraft");
        assertEquals(204, resp.status());
    }
}
