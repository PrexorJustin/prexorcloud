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

    @Test
    @Order(4)
    void audit_cursorSeek_walksWholeLogWithoutOverlap() throws Exception {
        // Page through the entire log one entry at a time using nextCursor and
        // assert (a) each page honours pageSize, (b) no id appears twice across
        // pages, (c) the walk covers every entry the offset path returns.
        int total =
                admin.get("/api/v1/audit?limit=500&offset=0").json().get("data").size();
        assertTrue(total >= 2, "setup should have produced at least two audit entries");

        var seenIds = new java.util.LinkedHashSet<String>();
        String cursor = ""; // blank cursor = newest page
        int guard = 0;
        while (cursor != null && guard++ < total + 5) {
            var resp = admin.get("/api/v1/audit?cursor=" + cursor + "&pageSize=1");
            assertEquals(200, resp.status());
            var body = resp.json();
            var data = body.get("data");
            assertTrue(data.size() <= 1, "pageSize=1 must cap the page at one entry");
            for (var e : data) {
                assertTrue(seenIds.add(e.get("id").asText()), "cursor pages must not overlap");
            }
            var next = body.get("nextCursor");
            cursor = (next == null || next.isNull()) ? null : next.asText();
        }
        assertEquals(total, seenIds.size(), "seek walk should cover every entry exactly once");
    }

    @Test
    @Order(5)
    void audit_cursorSeek_rejectsGarbageCursor() throws Exception {
        var resp = admin.get("/api/v1/audit?cursor=not-an-object-id");
        assertEquals(400, resp.status());
    }
}
