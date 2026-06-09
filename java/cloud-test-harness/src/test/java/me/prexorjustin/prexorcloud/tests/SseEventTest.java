package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.SseListener;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for SSE event streaming at /api/v1/events/stream.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SseEventTest {

    static TestCluster cluster;
    static RestClient admin;

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "Mongo test dependency is not reachable");
        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    @Test
    @Order(1)
    void connect_validToken_receivesConnectedEvent() throws Exception {
        try (var listener = SseListener.connectToEventStream(cluster.restBaseUrl(), cluster.adminJwtToken())) {
            assertTrue(listener.awaitConnected(5000), "Should receive connected event");
        }
    }

    @Test
    @Order(2)
    void reconnectAfterControllerRestart_replaysRedisBackedEvents() throws Exception {
        Assumptions.assumeTrue(TestCluster.redisAvailable(), "Redis test dependency is not reachable");

        if (cluster != null) {
            cluster.close();
        }
        cluster = TestCluster.startWithRedis();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

        long baselineSequence;
        try (var listener = SseListener.connectToEventStream(cluster.restBaseUrl(), cluster.adminJwtToken())) {
            var connected = listener.awaitEvent("connected", 5000);
            assertNotNull(connected, "Expected connected event before establishing replay baseline");
            baselineSequence = connected.json().path("latestSequence").asLong();
        }

        String groupName = "sse-restart-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post(
                        "/api/v1/groups",
                        Map.of(
                                "name",
                                groupName,
                                "platform",
                                "PAPER",
                                "platformVersion",
                                "1.21.1",
                                "minInstances",
                                0,
                                "maxInstances",
                                5,
                                "maxPlayers",
                                50))
                .assertStatus(201);

        cluster.restartController();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

        try (var listener =
                SseListener.connectToEventStream(cluster.restBaseUrl(), cluster.adminJwtToken(), baselineSequence)) {
            assertTrue(listener.awaitConnected(5000), "Reconnect should establish an SSE session");
            var replayed = listener.awaitEvent("GROUP_CREATED", 10000);
            assertNotNull(
                    replayed,
                    "Expected Redis-backed GROUP_CREATED replay after controller restart. Events: "
                            + listener.getAll().stream()
                                    .map(event -> event.eventType() + ":" + event.data())
                                    .toList());
            assertEquals(groupName, replayed.json().path("groupName").asText());
            assertTrue(
                    replayed.json().path("sequence").asLong() > baselineSequence,
                    "Replayed event should advance the event sequence");
        } finally {
            admin.delete("/api/v1/groups/" + groupName);
        }
    }

    @Test
    @Order(3)
    void reconnectAfterTemporaryDisconnect_replaysMissedRedisEvents() throws Exception {
        Assumptions.assumeTrue(TestCluster.redisAvailable(), "Redis test dependency is not reachable");

        if (cluster == null) {
            cluster = TestCluster.startWithRedis();
            admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
        }

        long baselineSequence;
        try (var listener = SseListener.connectToEventStream(cluster.restBaseUrl(), cluster.adminJwtToken())) {
            var connected = listener.awaitEvent("connected", 5000);
            assertNotNull(connected, "Expected connected event before simulating disconnect");
            baselineSequence = connected.json().path("latestSequence").asLong();
        }

        String groupName = "sse-disconnect-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post(
                        "/api/v1/groups",
                        Map.of(
                                "name",
                                groupName,
                                "platform",
                                "PAPER",
                                "platformVersion",
                                "1.21.1",
                                "minInstances",
                                0,
                                "maxInstances",
                                5,
                                "maxPlayers",
                                50))
                .assertStatus(201);

        try (var listener =
                SseListener.connectToEventStream(cluster.restBaseUrl(), cluster.adminJwtToken(), baselineSequence)) {
            assertTrue(listener.awaitConnected(5000), "Reconnect should establish an SSE session");
            var replayed = listener.awaitEvent("GROUP_CREATED", 10000);
            assertNotNull(
                    replayed,
                    "Expected Redis-backed GROUP_CREATED replay after disconnect. Events: "
                            + listener.getAll().stream()
                                    .map(event -> event.eventType() + ":" + event.data())
                                    .toList());
            assertEquals(groupName, replayed.json().path("groupName").asText());
            assertTrue(
                    replayed.json().path("sequence").asLong() > baselineSequence,
                    "Replayed event should advance the event sequence after reconnect");
        } finally {
            admin.delete("/api/v1/groups/" + groupName);
        }
    }

    @Test
    @Order(10)
    @Disabled("java.net.http.HttpClient doesn't support true SSE streaming — needs okhttp-sse or raw socket client")
    void groupCreated_firesEvent() throws Exception {
        try (var listener = SseListener.connectToEventStream(cluster.restBaseUrl(), cluster.adminJwtToken())) {
            assertTrue(listener.awaitConnected(5000));
            Thread.sleep(500); // Wait for SSE subscription to be fully active
            listener.clear();

            // Create a group — should fire GROUP_CREATED
            admin.post(
                    "/api/v1/groups",
                    Map.of(
                            "name",
                            "sse-test-group",
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

            // Wait for SSE event (check both SSE event type and JSON type field)
            var event = listener.awaitEvent("GROUP_CREATED", 10000);
            assertNotNull(
                    event,
                    "Should receive GROUP_CREATED event. Events received: "
                            + listener.getAll().stream()
                                    .map(e -> e.eventType() + ":" + e.data())
                                    .toList());

            // Cleanup
            admin.delete("/api/v1/groups/sse-test-group");
        }
    }

    @Test
    @Order(11)
    @Disabled("java.net.http.HttpClient doesn't support true SSE streaming — needs okhttp-sse or raw socket client")
    void groupDeleted_firesEvent() throws Exception {
        admin.post(
                "/api/v1/groups",
                Map.of(
                        "name",
                        "sse-del-grp",
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

        try (var listener = SseListener.connectToEventStream(cluster.restBaseUrl(), cluster.adminJwtToken())) {
            assertTrue(listener.awaitConnected(5000));
            Thread.sleep(500);
            listener.clear();

            admin.delete("/api/v1/groups/sse-del-grp");

            var event = listener.awaitEvent("GROUP_DELETED", 10000);
            assertNotNull(
                    event,
                    "Should receive GROUP_DELETED event. All events: "
                            + listener.getAll().stream()
                                    .map(e -> e.eventType() + ":"
                                            + (e.json() != null ? e.json().get("type") : ""))
                                    .toList());
        }
    }

    @Test
    @Order(12)
    @Disabled("java.net.http.HttpClient doesn't support true SSE streaming — needs okhttp-sse or raw socket client")
    void groupUpdated_firesEvent() throws Exception {
        admin.post(
                "/api/v1/groups",
                Map.of(
                        "name",
                        "sse-upd-grp",
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

        try (var listener = SseListener.connectToEventStream(cluster.restBaseUrl(), cluster.adminJwtToken())) {
            assertTrue(listener.awaitConnected(5000));
            Thread.sleep(500);
            listener.clear();

            admin.patch("/api/v1/groups/sse-upd-grp", Map.of("maxPlayers", 100));

            var event = listener.awaitEvent("GROUP_UPDATED", 10000);
            assertNotNull(
                    event,
                    "Should receive GROUP_UPDATED event. All events: "
                            + listener.getAll().stream()
                                    .map(e -> e.eventType() + ":"
                                            + (e.json() != null ? e.json().get("type") : ""))
                                    .toList());

            admin.delete("/api/v1/groups/sse-upd-grp");
        }
    }

    // SSE auth tests

    @Test
    @Order(20)
    void connect_noToken_receivesError() throws Exception {
        try (var listener = SseListener.connectToEventStream(cluster.restBaseUrl(), "")) {
            // Should not connect successfully
            Thread.sleep(1000);
            var errors = listener.getByType("error");
            if (errors.isEmpty()) {
                // Connection may have been rejected entirely
                assertFalse(listener.awaitConnected(2000));
            }
        }
    }

    @Test
    @Order(21)
    void connect_invalidToken_receivesError() throws Exception {
        try (var listener = SseListener.connectToEventStream(cluster.restBaseUrl(), "invalid.jwt.token")) {
            Thread.sleep(1000);
            var errors = listener.getByType("error");
            if (errors.isEmpty()) {
                assertFalse(listener.awaitConnected(2000));
            }
        }
    }
}
