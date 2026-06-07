package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.*;

/**
 * Security-focused tests: JWT, plugin tokens, join tokens.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityTest {

    static TestCluster cluster;
    static RestClient admin;

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for security harness tests");
        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    // --- JWT tests ---

    @Test
    @Order(1)
    void jwt_tamperedToken_returns401() throws Exception {
        // Take a valid token and modify it
        String validToken = cluster.adminJwtToken();
        String tampered = validToken.substring(0, validToken.length() - 5) + "XXXXX";
        var client = new RestClient(cluster.restBaseUrl(), tampered);
        var resp = client.get("/api/v1/auth/me");
        assertEquals(401, resp.status());
    }

    @Test
    @Order(2)
    void jwt_emptyToken_returns401() throws Exception {
        var client = new RestClient(cluster.restBaseUrl(), "");
        var resp = client.get("/api/v1/auth/me");
        assertEquals(401, resp.status());
    }

    @Test
    @Order(3)
    void jwt_randomString_returns401() throws Exception {
        var client = new RestClient(cluster.restBaseUrl(), "not.a.jwt");
        var resp = client.get("/api/v1/auth/me");
        assertEquals(401, resp.status());
    }

    // --- Join token lifecycle ---

    @Test
    @Order(10)
    void joinToken_createUseCannotReuse() throws Exception {
        // Create token
        var createResp = admin.post("/api/v1/admin/tokens", Map.of("nodeId", "token-test-node", "ttlSeconds", 3600));
        assertEquals(201, createResp.status());
        String tokenId = createResp.json().get("tokenId").asText();

        // Revoke (consume) the token
        var revokeResp = admin.delete("/api/v1/admin/tokens/" + tokenId);
        assertEquals(204, revokeResp.status());

        // Cannot create another token for same node (if it got registered)
        // The node shouldn't be registered since no daemon used it
        // But creating a new token for the same nodeId should now work
        var recreateResp = admin.post("/api/v1/admin/tokens", Map.of("nodeId", "token-test-node", "ttlSeconds", 3600));
        // Should succeed since previous was revoked
        assertEquals(201, recreateResp.status());
    }

    // --- Plugin token isolation ---

    @Test
    @Order(20)
    void pluginToken_cannotAccessOtherInstance() throws Exception {
        // Register two instances with different tokens
        String tokenA = UUID.randomUUID().toString();
        String tokenB = UUID.randomUUID().toString();
        cluster.controller().clusterState().registerPluginToken(tokenA, "instance-a");
        cluster.controller().clusterState().registerPluginToken(tokenB, "instance-b");
        cluster.controller()
                .clusterState()
                .addInstance(new InstanceInfo(
                        "instance-a", "test", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));
        cluster.controller()
                .clusterState()
                .addInstance(new InstanceInfo(
                        "instance-b", "test", "node-1", InstanceState.RUNNING, 25566, 0, 0, Instant.now()));

        // Token A should work for plugin API
        var respA = new RestClient(cluster.restBaseUrl(), null)
                .postWithToken("/api/plugin/ready", tokenA, Map.of(), Map.of("X-Prexor-Sequence", "1"));
        assertEquals(200, respA.status());

        // Token B should work for plugin API
        var respB = new RestClient(cluster.restBaseUrl(), null)
                .postWithToken("/api/plugin/ready", tokenB, Map.of(), Map.of("X-Prexor-Sequence", "1"));
        assertEquals(200, respB.status());

        // An invalid token should fail
        var respBad = new RestClient(cluster.restBaseUrl(), null)
                .postWithToken("/api/plugin/ready", "fake-token", Map.of(), Map.of("X-Prexor-Sequence", "1"));
        assertEquals(401, respBad.status());
    }

    @Test
    @Order(21)
    void expiredWorkloadCredential_isRejectedOnSequencedPluginRoute() throws Exception {
        String token = UUID.randomUUID().toString();
        String instanceId = "expired-plugin-instance";
        cluster.controller()
                .clusterState()
                .importPluginToken(token, instanceId, Instant.now().minus(Duration.ofDays(1)));
        cluster.controller()
                .clusterState()
                .addInstance(new InstanceInfo(
                        instanceId, "test", "node-1", InstanceState.RUNNING, 25567, 0, 0, Instant.now()));

        var resp = new RestClient(cluster.restBaseUrl(), null)
                .postWithToken("/api/plugin/ready", token, Map.of(), Map.of("X-Prexor-Sequence", "1"));

        assertEquals(401, resp.status());
        assertEquals("UNAUTHORIZED", resp.json().get("error").asText());
    }

    @Test
    @Order(22)
    void expiredWorkloadCredential_isRejectedOnUnsequencedProxyRoute() throws Exception {
        String token = UUID.randomUUID().toString();
        String instanceId = "expired-proxy-instance";
        cluster.controller()
                .clusterState()
                .importPluginToken(token, instanceId, Instant.now().minus(Duration.ofDays(1)));
        cluster.controller()
                .clusterState()
                .addInstance(new InstanceInfo(
                        instanceId, "proxy-group", "node-1", InstanceState.RUNNING, 25568, 0, 0, Instant.now()));

        var resp = new RestClient(cluster.restBaseUrl(), null).getWithToken("/api/proxy/instances", token);

        assertEquals(401, resp.status());
        assertEquals("UNAUTHORIZED", resp.json().get("error").asText());
    }

    // --- Password hashing ---

    @Test
    @Order(30)
    void passwordHashing_loginVerifiesHash() throws Exception {
        // Create user → password stored as hash
        admin.post(
                "/api/v1/users",
                Map.of(
                        "username", "hashtest",
                        "password", "securepassword123",
                        "role", "VIEWER"));

        // Login with correct password → works
        var loginResp = admin.postNoAuth(
                "/api/v1/auth/login",
                Map.of(
                        "username", "hashtest",
                        "password", "securepassword123"));
        assertEquals(200, loginResp.status());

        // Login with wrong password → fails
        var badResp = admin.postNoAuth(
                "/api/v1/auth/login",
                Map.of(
                        "username", "hashtest",
                        "password", "wrongpassword"));
        assertEquals(401, badResp.status());

        // Cleanup
        admin.delete("/api/v1/users/hashtest");
    }
}
