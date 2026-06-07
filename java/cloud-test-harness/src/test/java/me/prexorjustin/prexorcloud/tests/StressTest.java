package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.harness.FakeProxy;
import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.SseListener;
import me.prexorjustin.prexorcloud.harness.TestCluster;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;

/**
 * Stress tests — tagged with "stress" for separate CI execution.
 */
@Tag("stress")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StressTest {

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

    @Test
    @Order(1)
    void playerFlood_1000_concurrent_joinLeave() throws Exception {
        String proxyToken = UUID.randomUUID().toString();
        String proxyId = "stress-proxy";
        cluster.controller().clusterState().registerPluginToken(proxyToken, proxyId);
        cluster.controller()
                .clusterState()
                .addInstance(new InstanceInfo(
                        proxyId, "proxy", "node-1", InstanceState.RUNNING, 25577, 0, 0, Instant.now()));

        var proxy = new FakeProxy(cluster.restBaseUrl(), proxyToken);
        int count = 1000;
        var players = new ArrayList<UUID>();
        var errors = new AtomicInteger(0);

        // Join all
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var latch = new CountDownLatch(count);
            for (int i = 0; i < count; i++) {
                UUID uuid = UUID.randomUUID();
                players.add(uuid);
                final String name = "Flood" + i;
                executor.submit(() -> {
                    try {
                        var resp = proxy.playerJoin(uuid, name, proxyId, "lobby");
                        if (resp.status() != 200) errors.incrementAndGet();
                    } catch (Exception _) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(60, TimeUnit.SECONDS));
        }

        assertEquals(0, errors.get(), "No join errors should occur");
        assertEquals(count, cluster.controller().clusterState().playerCount());

        // Leave all
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var latch = new CountDownLatch(count);
            for (UUID uuid : players) {
                executor.submit(() -> {
                    try {
                        proxy.playerLeave(uuid);
                    } catch (Exception _) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(60, TimeUnit.SECONDS));
        }

        assertEquals(0, cluster.controller().clusterState().playerCount());
    }

    @Test
    @Order(2)
    void concurrentRestRequests_noServerErrors() throws Exception {
        int count = 100;
        var errors = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var latch = new CountDownLatch(count);
            for (int i = 0; i < count; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        var client = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
                        // Mix of different endpoint types
                        switch (idx % 5) {
                            case 0 -> client.get("/api/v1/groups");
                            case 1 -> client.get("/api/v1/nodes");
                            case 2 -> client.get("/api/v1/services");
                            case 3 -> client.get("/api/v1/players");
                            case 4 -> client.get("/api/v1/overview");
                        }
                    } catch (Exception _) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        }

        assertEquals(0, errors.get(), "No REST errors should occur under concurrent load");
    }

    @Test
    @Order(3)
    void groupCreateDeleteCycle_noStateLeak() throws Exception {
        int cycles = 100;

        for (int i = 0; i < cycles; i++) {
            String name = "leak-test-" + i;
            admin.post(
                    "/api/v1/groups",
                    Map.of(
                            "name",
                            name,
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
            admin.delete("/api/v1/groups/" + name);
        }

        // Verify no leaked groups
        var resp = admin.get("/api/v1/groups");
        var groups = resp.asList();
        assertTrue(groups.stream().noneMatch(g -> ((String) g.get("name")).startsWith("leak-test-")));
    }

    @Test
    @Order(4)
    void sseFanOut_50clients_allReceiveEvents() throws Exception {
        int clientCount = 50;
        var listeners = new ArrayList<SseListener>();

        try {
            // Connect all clients
            for (int i = 0; i < clientCount; i++) {
                var listener = SseListener.connectToEventStream(cluster.restBaseUrl(), cluster.adminJwtToken());
                listeners.add(listener);
            }

            // Wait for all to connect
            for (var listener : listeners) {
                assertTrue(listener.awaitConnected(10000), "All SSE clients should connect");
            }

            // Clear events
            listeners.forEach(SseListener::clear);

            // Trigger an event
            admin.post(
                    "/api/v1/groups",
                    Map.of(
                            "name",
                            "sse-fanout-test",
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

            // Wait and verify all received
            Thread.sleep(2000);
            int received = 0;
            for (var listener : listeners) {
                if (!listener.getByType("GROUP_CREATED").isEmpty()) {
                    received++;
                }
            }

            assertTrue(
                    received >= clientCount * 0.9,
                    "At least 90% of SSE clients should receive the event, got " + received + "/" + clientCount);

            admin.delete("/api/v1/groups/sse-fanout-test");
        } finally {
            listeners.forEach(SseListener::close);
        }
    }
}
