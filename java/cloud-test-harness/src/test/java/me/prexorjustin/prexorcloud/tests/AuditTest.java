package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.*;

/**
 * Tests for /api/v1/audit endpoint.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditTest {

    static TestCluster cluster;
    static RestClient admin;

    @BeforeAll
    static void setup() throws Exception {
        cluster = TestCluster.start();
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

        // Perform some actions to generate audit log entries
        admin.post(
                "/api/v1/users",
                Map.of(
                        "username", "audituser",
                        "password", "password123",
                        "role", "VIEWER"));
        admin.delete("/api/v1/users/audituser");
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    @Test
    @Order(1)
    void audit_defaultLimitOffset_returnsEntries() throws Exception {
        var resp = admin.get("/api/v1/audit");
        assertEquals(200, resp.status());
        var entries = resp.asList();
        assertFalse(entries.isEmpty());
    }

    @Test
    @Order(2)
    void audit_customLimitOffset_works() throws Exception {
        var resp = admin.get("/api/v1/audit?limit=5&offset=0");
        assertEquals(200, resp.status());
        var entries = resp.asList();
        assertTrue(entries.size() <= 5);
    }

    @Test
    @Order(3)
    void audit_containsUserActions() throws Exception {
        var resp = admin.get("/api/v1/audit");
        assertEquals(200, resp.status());
        var entries = resp.asList();

        // Should contain user.create and user.delete from setup
        boolean hasCreate = entries.stream().anyMatch(e -> "user.create".equals(e.get("action")));
        boolean hasDelete = entries.stream().anyMatch(e -> "user.delete".equals(e.get("action")));
        assertTrue(hasCreate, "Audit log should contain user.create");
        assertTrue(hasDelete, "Audit log should contain user.delete");
    }
}
