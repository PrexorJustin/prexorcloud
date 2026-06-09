package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.harness.FakeMinecraftServer;
import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/plugin/* endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PluginApiTest {

    static TestCluster cluster;
    static RestClient admin;
    static FakeMinecraftServer server;
    static String instanceId = "lobby-1";

    @BeforeAll
    static void setup() throws Exception {
        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

        // Register a fake game server instance with a plugin token
        String pluginToken = UUID.randomUUID().toString();
        cluster.controller().clusterState().registerPluginToken(pluginToken, instanceId);
        cluster.controller()
                .clusterState()
                .addInstance(new InstanceInfo(
                        instanceId, "lobby", "test-node", InstanceState.STARTING, 25565, 0, 0, Instant.now()));

        server = new FakeMinecraftServer(cluster.restBaseUrl(), pluginToken);
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    // --- POST /api/plugin/ready ---

    @Test
    @Order(1)
    void ready_marksInstanceRunning() throws Exception {
        var resp = server.ready();
        assertEquals(200, resp.status());

        // Verify state changed to RUNNING
        var instance = cluster.controller().clusterState().getInstance(instanceId);
        assertTrue(instance.isPresent());
        assertEquals(InstanceState.RUNNING, instance.get().state());
    }

    // --- POST /api/plugin/player-join ---

    @Test
    @Order(10)
    void playerJoin_valid_addsPlayer() throws Exception {
        UUID playerUuid = UUID.fromString("660e8400-e29b-41d4-a716-446655440002");
        var resp = server.playerJoin(playerUuid, "GamePlayer", "lobby");
        assertEquals(200, resp.status());

        // Verify player in cluster state
        var player = cluster.controller().clusterState().getPlayer(playerUuid);
        assertTrue(player.isPresent());
        assertEquals("GamePlayer", player.get().name());
        assertEquals(instanceId, player.get().instanceId());
    }

    @Test
    @Order(11)
    void playerJoin_autoDetectsGroup() throws Exception {
        UUID playerUuid = UUID.fromString("660e8400-e29b-41d4-a716-446655440003");
        // Join without explicit group — should auto-detect from instance
        var resp = server.playerJoin(playerUuid, "AutoGroupPlayer");
        assertEquals(200, resp.status());
    }

    // --- POST /api/plugin/player-leave ---

    @Test
    @Order(20)
    void playerLeave_removesPlayer() throws Exception {
        UUID playerUuid = UUID.fromString("660e8400-e29b-41d4-a716-446655440002");
        var resp = server.playerLeave(playerUuid);
        assertEquals(200, resp.status());

        var player = cluster.controller().clusterState().getPlayer(playerUuid);
        assertTrue(player.isEmpty());
    }

    // --- POST /api/plugin/events ---

    @Test
    @Order(30)
    void fireEvent_customEvent_works() throws Exception {
        var resp = server.fireEvent("PLUGIN:TEST_EVENT", Map.of("message", "Hello from plugin"));
        assertEquals(200, resp.status());
    }

    // --- POST /api/plugin/metrics ---

    @Test
    @Order(40)
    void reportMetrics_fullPayload_works() throws Exception {
        var resp = server.reportMetrics(20.0, 512, 1024, 5, 50);
        assertEquals(200, resp.status());

        // Verify metrics stored
        var metricsResp = admin.get("/api/v1/services/" + instanceId + "/metrics");
        assertEquals(200, metricsResp.status());
        var json = metricsResp.json();
        assertEquals(5, json.get("playerCount").asInt());
    }

    // --- Auth tests ---

    @Test
    @Order(50)
    void ready_noToken_returns401() throws Exception {
        var resp = new RestClient(cluster.restBaseUrl(), null).postNoAuth("/api/plugin/ready", Map.of());
        assertEquals(401, resp.status());
    }

    @Test
    @Order(51)
    void ready_invalidToken_returns401() throws Exception {
        var resp = new RestClient(cluster.restBaseUrl(), null)
                .postWithToken("/api/plugin/ready", "bad-token", Map.of(), Map.of("X-Prexor-Sequence", "1"));
        assertEquals(401, resp.status());
    }
}
