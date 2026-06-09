package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/auth/* endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthTest {

    static TestCluster cluster;
    static RestClient client;

    @BeforeAll
    static void setup() throws Exception {
        cluster = TestCluster.start();
        client = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    // --- POST /api/v1/auth/login ---

    @Test
    @Order(1)
    void login_validCredentials_returnsTokenAndUser() throws Exception {
        var resp = client.postNoAuth(
                "/api/v1/auth/login", Map.of("username", "admin", "password", cluster.adminPassword()));
        assertEquals(200, resp.status());
        var json = resp.json();
        assertNotNull(json.get("token").asText());
        assertFalse(json.get("token").asText().isEmpty());
        assertEquals("admin", json.get("user").get("username").asText());
        assertEquals("ADMIN", json.get("user").get("role").asText());
    }

    @Test
    @Order(2)
    void login_wrongPassword_returns401() throws Exception {
        var resp = client.postNoAuth(
                "/api/v1/auth/login",
                Map.of(
                        "username", "admin",
                        "password", "wrongpassword"));
        assertEquals(401, resp.status());
    }

    @Test
    @Order(3)
    void login_unknownUser_returns401() throws Exception {
        var resp = client.postNoAuth(
                "/api/v1/auth/login",
                Map.of(
                        "username", "nonexistent",
                        "password", "anything"));
        assertEquals(401, resp.status());
    }

    @Test
    @Order(4)
    void login_emptyBody_returnsError() throws Exception {
        var resp = client.postNoAuth("/api/v1/auth/login", Map.of());
        // Empty body may cause 400, 401, or 500 depending on serialization
        assertTrue(resp.status() >= 400, "Should return error status, got " + resp.status());
    }

    // --- GET /api/v1/auth/me ---

    @Test
    @Order(10)
    void me_validToken_returnsUser() throws Exception {
        var resp = client.get("/api/v1/auth/me");
        assertEquals(200, resp.status());
        assertEquals("admin", resp.json().get("username").asText());
    }

    @Test
    @Order(11)
    void me_noToken_returns401() throws Exception {
        var resp = client.getNoAuth("/api/v1/auth/me");
        assertEquals(401, resp.status());
    }

    @Test
    @Order(12)
    void me_invalidToken_returns401() throws Exception {
        var noAuthClient = new RestClient(cluster.restBaseUrl(), "totally.invalid.token");
        var resp = noAuthClient.get("/api/v1/auth/me");
        assertEquals(401, resp.status());
    }

    // --- POST /api/v1/auth/refresh ---

    @Test
    @Order(20)
    void refresh_validToken_returnsNewToken() throws Exception {
        var resp = client.postEmpty("/api/v1/auth/refresh");
        assertEquals(200, resp.status());
        assertNotNull(resp.json().get("token").asText());
        assertFalse(resp.json().get("token").asText().isEmpty());
    }

    @Test
    @Order(21)
    void refresh_noToken_returns401() throws Exception {
        var resp = client.postNoAuth("/api/v1/auth/refresh", Map.of());
        assertEquals(401, resp.status());
    }

    // --- POST /api/v1/auth/change-password ---

    @Test
    @Order(30)
    void changePassword_wrongCurrentPassword_returns401() throws Exception {
        var resp = client.post(
                "/api/v1/auth/change-password",
                Map.of(
                        "currentPassword", "wrongcurrent",
                        "newPassword", "newpassword123"));
        assertEquals(401, resp.status());
    }

    @Test
    @Order(31)
    void changePassword_newPasswordTooShort_returns422() throws Exception {
        var resp = client.post(
                "/api/v1/auth/change-password",
                Map.of("currentPassword", cluster.adminPassword(), "newPassword", "short"));
        assertEquals(422, resp.status());
    }

    @Test
    @Order(32)
    void changePassword_valid_returnsOk() throws Exception {
        // Create a test user, then change their password
        client.post(
                "/api/v1/users",
                Map.of(
                        "username", "pwtest",
                        "password", "password123",
                        "role", "VIEWER"));

        String token = cluster.loginAs("pwtest", "password123");
        var userClient = new RestClient(cluster.restBaseUrl(), token);

        var resp = userClient.post(
                "/api/v1/auth/change-password",
                Map.of(
                        "currentPassword", "password123",
                        "newPassword", "newpassword456"));
        assertEquals(200, resp.status());

        // Verify new password works
        var loginResp = client.postNoAuth(
                "/api/v1/auth/login",
                Map.of(
                        "username", "pwtest",
                        "password", "newpassword456"));
        assertEquals(200, loginResp.status());

        // Cleanup
        client.delete("/api/v1/users/pwtest");
    }
}
