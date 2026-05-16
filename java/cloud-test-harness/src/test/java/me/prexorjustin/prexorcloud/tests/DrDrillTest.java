package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Nightly DR drill (Phase 41 / gap #16). End-to-end exercise of
 * {@code POST /api/v1/backups} → wipe Mongo + Valkey → restart controller →
 * {@code POST /api/v1/restore}, asserting the restored controller is
 * indistinguishable from the pre-incident snapshot.
 *
 * <p>Tagged {@code dr} so the regular test pass skips it; opt in via the
 * {@code drDrill} gradle task or the nightly CI workflow.
 */
@Tag("dr")
class DrDrillTest {

    @Test
    void backupRestoreCycle_recoversFullDeclarativeState() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for the DR drill");
        Assumptions.assumeTrue(TestCluster.redisAvailable(), "Redis/Valkey is required for the DR drill");

        try (TestCluster cluster = TestCluster.startWithRedis()) {
            RestClient admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

            seedFixture(admin);

            Snapshot before = snapshot(admin);
            assertFalse(before.groupNames().isEmpty(), "fixture seed must produce at least one group");

            // Create a backup + verify it before we wipe anything.
            var createResp = admin.post("/api/v1/backups", Map.of()).assertStatus(201);
            String backupId = createResp.json().get("id").asText();
            assertNotNull(backupId);
            assertFalse(backupId.isBlank());

            var verifyResp =
                    admin.postEmpty("/api/v1/backups/" + backupId + "/verify").assertStatus(200);
            assertTrue(
                    verifyResp.json().get("valid").asBoolean(), "fresh backup must verify clean: " + verifyResp.body());

            // Simulate the catastrophe: stop controller, wipe Mongo + Valkey,
            // bring controller back. Backups directory lives in the controller
            // working directory, which survives the restart.
            cluster.stopController();
            cluster.wipeDatastores();
            cluster.startControllerAfterStop();

            RestClient restored = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

            // Sanity: declarative state really is gone. (Default templates may
            // still be re-seeded by bootstrap; we only assert the user-created
            // fixtures vanished.)
            Snapshot wiped = snapshot(restored);
            for (String name : before.groupNames()) {
                assertFalse(
                        wiped.groupNames().contains(name),
                        "group " + name + " survived the wipe — DR drill premise broken");
            }

            // Dry-run first — catches the gap before APPLY touches state.
            var dryRun = restored.post(
                            "/api/v1/restore",
                            Map.of("id", backupId, "dryRun", true, "filesystem", true, "datastores", true))
                    .assertStatus(200);
            assertTrue(dryRun.json().get("dryRun").asBoolean(), "dryRun must echo true");

            // Apply. After this the controller has to look like it did pre-wipe.
            restored.post(
                            "/api/v1/restore",
                            Map.of("id", backupId, "dryRun", false, "filesystem", true, "datastores", true))
                    .assertStatus(200);

            // The mongo restore swaps out the user collection too — re-login
            // before re-snapshotting so we read state with a token signed
            // against the post-restore admin record.
            String reissued = cluster.loginAs("admin", cluster.adminPassword());
            restored.setToken(reissued);

            Snapshot after = snapshot(restored);
            assertSnapshotsMatch(before, after);
        }
    }

    private static void seedFixture(RestClient admin) throws Exception {
        admin.post(
                        "/api/v1/templates",
                        Map.of(
                                "name", "dr-drill-template",
                                "description", "DR drill fixture template",
                                "platform", "PAPER"))
                .assertStatus(201);

        admin.post(
                        "/api/v1/groups",
                        Map.of(
                                "name", "dr-drill-lobby",
                                "platform", "PAPER",
                                "platformVersion", "1.21.1",
                                "minInstances", 0,
                                "maxInstances", 2,
                                "maxPlayers", 50,
                                "priority", 5))
                .assertStatus(201);

        admin.post(
                        "/api/v1/groups",
                        Map.of(
                                "name", "dr-drill-survival",
                                "platform", "PAPER",
                                "platformVersion", "1.21.1",
                                "minInstances", 0,
                                "maxInstances", 4,
                                "maxPlayers", 100,
                                "priority", 10))
                .assertStatus(201);
    }

    private static Snapshot snapshot(RestClient admin) throws Exception {
        var groupsResp = admin.get("/api/v1/groups").assertStatus(200);
        List<String> groupNames = new ArrayList<>();
        Map<String, Map<String, Object>> groupsByName = new LinkedHashMap<>();
        for (JsonNode node : groupsResp.json()) {
            String name = node.get("name").asText();
            groupNames.add(name);
            groupsByName.put(
                    name,
                    Map.of(
                            "platform", textOr(node, "platform", ""),
                            "platformVersion", textOr(node, "platformVersion", ""),
                            "minInstances",
                                    node.has("minInstances")
                                            ? node.get("minInstances").asInt()
                                            : 0,
                            "maxInstances",
                                    node.has("maxInstances")
                                            ? node.get("maxInstances").asInt()
                                            : 0,
                            "maxPlayers",
                                    node.has("maxPlayers")
                                            ? node.get("maxPlayers").asInt()
                                            : 0,
                            "priority",
                                    node.has("priority") ? node.get("priority").asInt() : 0));
        }
        groupNames.sort(String::compareTo);

        var templatesResp = admin.get("/api/v1/templates").assertStatus(200);
        List<String> templateNames = new ArrayList<>();
        for (JsonNode node : templatesResp.json()) {
            templateNames.add(node.get("name").asText());
        }
        templateNames.sort(String::compareTo);

        return new Snapshot(groupNames, groupsByName, templateNames);
    }

    private static void assertSnapshotsMatch(Snapshot before, Snapshot after) {
        assertEquals(
                before.groupNames(), after.groupNames(), "post-restore group set diverged from pre-incident snapshot");
        assertEquals(
                before.templateNames(),
                after.templateNames(),
                "post-restore template set diverged from pre-incident snapshot");
        for (String name : before.groupNames()) {
            assertEquals(
                    before.groupsByName().get(name),
                    after.groupsByName().get(name),
                    "post-restore group " + name + " config diverged from pre-incident snapshot");
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        return node.get(field).asText();
    }

    private record Snapshot(
            List<String> groupNames, Map<String, Map<String, Object>> groupsByName, List<String> templateNames) {}
}
