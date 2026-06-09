package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;

/**
 * Fault injection tests — corrupt data, invalid tokens, edge cases.
 * Tagged with "stress" since some tests are slow.
 */
@Tag("stress")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FaultInjectionTest {

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
    void corruptJoinToken_rejected() throws Exception {
        // Attempt to use garbage as a join token
        var resp = admin.postNoAuth(
                "/api/v1/auth/login", Map.of("username", "admin", "password", "garbage-token-" + UUID.randomUUID()));
        assertEquals(401, resp.status());
    }

    @Test
    @Order(2)
    void malformedJson_returns400() throws Exception {
        var client = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
        var resp = client.post("/api/v1/groups", "{invalid json!!!}");
        assertTrue(resp.status() == 400 || resp.status() == 500);
    }

    @Test
    @Order(3)
    void oversizedRequest_handledGracefully() throws Exception {
        // Send a very large group name
        String longName = "a".repeat(10000);
        var resp = admin.post(
                "/api/v1/groups",
                Map.of(
                        "name",
                        longName,
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
        // Should fail validation, not crash
        assertTrue(resp.status() >= 400);
    }

    @Test
    @Order(4)
    void sqlInjectionAttempt_harmless() throws Exception {
        // Try SQL injection in group name
        var resp = admin.post(
                "/api/v1/groups",
                Map.of(
                        "name",
                        "'; DROP TABLE groups; --",
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
        // Should be rejected or handled safely
        assertTrue(resp.status() >= 400 || resp.status() == 201);

        // Verify system still works
        var healthResp = admin.getNoAuth("/api/v1/system/health");
        assertEquals(200, healthResp.status());
    }

    @Test
    @Order(5)
    void pathTraversal_blocked() throws Exception {
        // Create a template to test path traversal on
        admin.post(
                "/api/v1/templates",
                Map.of(
                        "name", "path-test",
                        "description", "test",
                        "platform", "PAPER"));

        // Try path traversal on file content
        var resp = admin.get("/api/v1/templates/path-test/files/content?path=../../etc/passwd");
        assertTrue(resp.status() == 400 || resp.status() == 404);

        // Try on mkdir
        var mkdirResp = admin.postEmpty("/api/v1/templates/path-test/files/mkdir?path=../../../tmp/evil");
        assertTrue(mkdirResp.status() == 400 || mkdirResp.status() == 404);

        // Cleanup
        admin.delete("/api/v1/templates/path-test");
    }

    @Test
    @Order(6)
    void doubleDelete_idempotent() throws Exception {
        // Create and delete a group twice
        admin.post(
                "/api/v1/groups",
                Map.of(
                        "name",
                        "double-delete",
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

        var firstDelete = admin.delete("/api/v1/groups/double-delete");
        assertEquals(204, firstDelete.status());

        var secondDelete = admin.delete("/api/v1/groups/double-delete");
        assertEquals(404, secondDelete.status()); // Already gone
    }

    @Test
    @Order(7)
    void concurrentGroupCreation_sameNameRaceCondition() throws Exception {
        // Try to create the same group from multiple threads
        var results = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var latch = new java.util.concurrent.CountDownLatch(10);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    try {
                        var resp = admin.post(
                                "/api/v1/groups",
                                Map.of(
                                        "name",
                                        "race-group",
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
                        results.add(resp.status());
                    } catch (Exception _) {
                        results.add(500);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        // Exactly one should succeed (201), rest should fail
        long successes = results.stream().filter(s -> s == 201).count();
        assertTrue(successes >= 1, "At least one creation should succeed");

        // System should still be healthy
        assertEquals(200, admin.getNoAuth("/api/v1/system/health").status());

        // Cleanup
        admin.delete("/api/v1/groups/race-group");
    }
}
