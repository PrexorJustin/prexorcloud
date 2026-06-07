package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.harness.FakeMinecraftServer;
import me.prexorjustin.prexorcloud.harness.FakeProxy;
import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.*;

/**
 * Player simulation tests — full player lifecycle, transfers, kicks, concurrent joins.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlayerSimulationTest {

    static TestCluster cluster;
    static RestClient admin;
    static FakeProxy proxy;
    static FakeMinecraftServer gameServer;
    static String proxyId = "sim-proxy-1";
    static String serverId = "sim-lobby-1";

    @BeforeAll
    static void setup() throws Exception {
        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

        // Register proxy
        String proxyToken = UUID.randomUUID().toString();
        cluster.controller().clusterState().registerPluginToken(proxyToken, proxyId);
        cluster.controller()
                .clusterState()
                .addInstance(new InstanceInfo(
                        proxyId, "proxy", "node-1", InstanceState.RUNNING, 25577, 0, 0, Instant.now()));
        proxy = new FakeProxy(cluster.restBaseUrl(), proxyToken);

        // Register game server
        String serverToken = UUID.randomUUID().toString();
        cluster.controller().clusterState().registerPluginToken(serverToken, serverId);
        cluster.controller()
                .clusterState()
                .addInstance(new InstanceInfo(
                        serverId, "lobby", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));
        gameServer = new FakeMinecraftServer(cluster.restBaseUrl(), serverToken);
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    // --- Full player flow ---

    @Test
    @Order(1)
    void fullPlayerFlow_proxyJoinGameJoinGameLeaveProxyLeave() throws Exception {
        UUID playerUuid = UUID.randomUUID();

        // 1. Proxy reports player join (network level)
        proxy.playerJoin(playerUuid, "FullFlowPlayer", proxyId, "lobby").assertStatus(200);

        // 2. Game server reports player join (server level)
        gameServer.playerJoin(playerUuid, "FullFlowPlayer", "lobby").assertStatus(200);

        // 3. Player should be visible via REST API
        var resp = admin.get("/api/v1/players/" + playerUuid);
        assertEquals(200, resp.status());
        assertEquals("FullFlowPlayer", resp.json().get("name").asText());

        // 4. Game server reports player leave
        gameServer.playerLeave(playerUuid).assertStatus(200);

        // 5. Proxy reports player leave
        proxy.playerLeave(playerUuid).assertStatus(200);

        // 6. Player should be gone
        var afterResp = admin.get("/api/v1/players/" + playerUuid);
        assertEquals(404, afterResp.status());
    }

    // --- Player transfer ---

    @Test
    @Order(10)
    void playerTransfer_queueAndAck() throws Exception {
        UUID playerUuid = UUID.randomUUID();

        // Register a second server as transfer target
        String server2Token = UUID.randomUUID().toString();
        cluster.controller().clusterState().registerPluginToken(server2Token, "sim-lobby-2");
        cluster.controller()
                .clusterState()
                .addInstance(new InstanceInfo(
                        "sim-lobby-2", "lobby", "node-1", InstanceState.RUNNING, 25566, 0, 0, Instant.now()));

        // Add player
        proxy.playerJoin(playerUuid, "TransferPlayer", proxyId, "lobby").assertStatus(200);

        // Queue transfer via REST API
        var transferResp =
                admin.post("/api/v1/players/" + playerUuid + "/transfer", Map.of("targetInstanceId", "sim-lobby-2"));
        assertEquals(200, transferResp.status());

        // Proxy polls pending transfers
        var pendingResp = proxy.getPendingTransfers();
        assertEquals(200, pendingResp.status());
        var pending = pendingResp.asList();
        assertTrue(pending.stream().anyMatch(p -> playerUuid.toString().equals(p.get("playerUuid"))));

        // Proxy acknowledges transfer
        proxy.ackTransfer(playerUuid).assertStatus(200);

        // Cleanup
        proxy.playerLeave(playerUuid);
    }

    // --- Player count tracking ---

    @Test
    @Order(20)
    void playerCountTracking_accurateAcrossInstances() throws Exception {
        var players = new ArrayList<UUID>();

        // Add 5 players
        for (int i = 0; i < 5; i++) {
            UUID uuid = UUID.randomUUID();
            players.add(uuid);
            proxy.playerJoin(uuid, "Player" + i, proxyId, "lobby").assertStatus(200);
        }

        // Verify total count
        assertEquals(5, cluster.controller().clusterState().playerCount());

        // Verify REST API count matches
        var overviewResp = admin.get("/api/v1/overview");
        assertEquals(5, overviewResp.json().get("playerCount").asInt());

        // Remove all
        for (UUID uuid : players) {
            proxy.playerLeave(uuid);
        }

        assertEquals(0, cluster.controller().clusterState().playerCount());
    }

    // --- Concurrent player joins ---

    @Test
    @Order(30)
    void concurrentPlayerJoins_100players_allTracked() throws Exception {
        int playerCount = 100;
        var players = new ArrayList<UUID>();
        var latch = new CountDownLatch(playerCount);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < playerCount; i++) {
                UUID uuid = UUID.randomUUID();
                players.add(uuid);
                final String name = "Concurrent" + i;
                executor.submit(() -> {
                    try {
                        proxy.playerJoin(uuid, name, proxyId, "lobby");
                    } catch (Exception e) {
                        fail("Player join failed: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS), "All joins should complete within 30s");
        }

        // Verify all tracked
        assertEquals(playerCount, cluster.controller().clusterState().playerCount());

        // Verify REST API
        var playersResp = admin.get("/api/v1/players");
        assertEquals(playerCount, playersResp.asList().size());

        // Cleanup
        for (UUID uuid : players) {
            proxy.playerLeave(uuid);
        }
    }
}
