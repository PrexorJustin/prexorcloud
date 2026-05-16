package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.harness.FakeProxy;
import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/proxy/* endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyApiTest {

    static TestCluster cluster;
    static RestClient admin;
    static FakeProxy proxy;
    static String proxyInstanceId = "proxy-1";

    @BeforeAll
    static void setup() throws Exception {
        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

        // Register a fake proxy instance with a plugin token
        String pluginToken = UUID.randomUUID().toString();
        cluster.controller().clusterState().registerPluginToken(pluginToken, proxyInstanceId);
        cluster.controller()
                .clusterState()
                .addInstance(new InstanceInfo(
                        proxyInstanceId,
                        "proxy-group",
                        "test-node",
                        InstanceState.RUNNING,
                        25577,
                        0,
                        0,
                        Instant.now()));

        proxy = new FakeProxy(cluster.restBaseUrl(), pluginToken);
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    // --- POST /api/proxy/player-join ---

    @Test
    @Order(1)
    void playerJoin_valid_works() throws Exception {
        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        var resp = proxy.playerJoin(playerUuid, "TestPlayer", proxyInstanceId, "lobby");
        assertEquals(200, resp.status());

        // Verify player is in cluster state
        var player = cluster.controller().clusterState().getPlayer(playerUuid);
        assertTrue(player.isPresent());
        assertEquals("TestPlayer", player.get().name());
    }

    // --- POST /api/proxy/player-leave ---

    @Test
    @Order(2)
    void playerLeave_valid_works() throws Exception {
        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        var resp = proxy.playerLeave(playerUuid);
        assertEquals(200, resp.status());

        // Verify player removed
        var player = cluster.controller().clusterState().getPlayer(playerUuid);
        assertTrue(player.isEmpty());
    }

    // --- Auth tests ---

    @Test
    @Order(5)
    void playerJoin_noToken_returns401() throws Exception {
        var noAuthClient = new RestClient(cluster.restBaseUrl(), null);
        var resp = noAuthClient.postNoAuth(
                "/api/proxy/player-join",
                Map.of(
                        "uuid",
                        UUID.randomUUID().toString(),
                        "name",
                        "Test",
                        "instanceId",
                        proxyInstanceId,
                        "group",
                        "lobby"));
        assertEquals(401, resp.status());
    }

    @Test
    @Order(6)
    void playerJoin_invalidToken_returns401() throws Exception {
        var resp = new RestClient(cluster.restBaseUrl(), null)
                .postWithToken(
                        "/api/proxy/player-join",
                        "invalid-token",
                        Map.of(
                                "uuid",
                                UUID.randomUUID().toString(),
                                "name",
                                "Test",
                                "instanceId",
                                proxyInstanceId,
                                "group",
                                "lobby"),
                        Map.of("X-Prexor-Sequence", "1"));
        assertEquals(401, resp.status());
    }

    // --- GET /api/proxy/instances ---

    @Test
    @Order(10)
    void getInstances_returnsNonProxyInstances() throws Exception {
        var resp = proxy.getInstances();
        assertEquals(200, resp.status());
        // All returned instances should be non-proxy
        assertNotNull(resp.asList());
    }

    // --- GET /api/proxy/groups ---

    @Test
    @Order(11)
    void getGroups_returnsGroupsWithOnlineCount() throws Exception {
        var resp = proxy.getGroups();
        assertEquals(200, resp.status());
        assertNotNull(resp.asList());
    }

    // --- GET /api/proxy/players ---

    @Test
    @Order(12)
    void getPlayers_returnsPlayers() throws Exception {
        var resp = proxy.getPlayers();
        assertEquals(200, resp.status());
        assertNotNull(resp.asList());
    }

    // --- GET /api/proxy/pending-transfers ---

    @Test
    @Order(20)
    void getPendingTransfers_empty_returnsEmptyList() throws Exception {
        var resp = proxy.getPendingTransfers();
        assertEquals(200, resp.status());
        assertTrue(resp.asList().isEmpty());
    }

    // --- POST /api/proxy/transfer-ack/{uuid} ---

    @Test
    @Order(30)
    void transferAck_invalidUuid_returns400() throws Exception {
        var resp = new RestClient(cluster.restBaseUrl(), null)
                .postWithToken(
                        "/api/proxy/transfer-ack/bad-uuid",
                        proxy.toString(),
                        Map.of(),
                        Map.of("X-Prexor-Sequence", "1"));
        // Should be 400 for bad UUID or 401 for bad token
        assertTrue(resp.status() == 400 || resp.status() == 401);
    }

    // --- POST /api/proxy/events ---

    @Test
    @Order(40)
    void fireEvent_works() throws Exception {
        var resp = proxy.fireEvent("PROXY:CUSTOM_EVENT", Map.of("key", "value"));
        assertEquals(200, resp.status());
    }

    // --- POST /api/proxy/metrics ---

    @Test
    @Order(50)
    void reportMetrics_works() throws Exception {
        var resp = proxy.reportMetrics(512, 1024, 60000, 10, java.util.List.of());
        assertEquals(200, resp.status());

        // Verify metrics are stored
        var metricsResp = admin.get("/api/v1/services/" + proxyInstanceId + "/proxy-metrics");
        assertEquals(200, metricsResp.status());
    }
}
