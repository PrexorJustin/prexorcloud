package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Epoch-fence behaviour for the single-writer control plane (finding #12): a deposed leader's
 * stale-epoch write must never clobber the live leader's state, while the live leader (and a
 * fence-disabled single-controller install) writes freely. Skipped when a local Mongo is not
 * reachable, matching {@link MongoStateStoreConsoleIT}.
 */
@DisplayName("MongoStateStore — epoch fence")
final class MongoStateStoreFenceIT {

    private static HealingActionIntent healing(String instanceId, String reason) {
        return new HealingActionIntent(instanceId, "grp", reason, Instant.parse("2026-06-24T10:00:00Z"));
    }

    private static Optional<HealingActionIntent> readHealing(MongoStateStore store, String instanceId) {
        return store.getHealingActionIntents().stream()
                .filter(h -> h.instanceId().equals(instanceId))
                .findFirst();
    }

    private static Long storedEpoch(MongoStateStore store, String collection, Object id) {
        Document doc = store.database()
                .getCollection(collection)
                .find(Filters.eq("_id", id))
                .first();
        return doc == null ? null : doc.getLong("ownerEpoch");
    }

    @Test
    @DisplayName("replace fence: live leader writes, deposed leader is dropped, new leader supersedes")
    void replaceFence() {
        Assumptions.assumeTrue(socketAvailable(resolveMongoUri(), 27017), "Mongo is not reachable");
        String dbName = "prexor-fence-it-" + UUID.randomUUID();
        try (var mongoClient = MongoClients.create(resolveMongoUri())) {
            var db = mongoClient.getDatabase(dbName);
            try {
                MongoStateStore store = new MongoStateStore(mongoClient, db);
                store.initialize();
                AtomicLong epoch = new AtomicLong(0L);
                store.setEpochSource(epoch::get);

                // Leader at epoch 5 writes (absent -> insert), stamps ownerEpoch=5.
                epoch.set(5L);
                store.saveHealingActionIntent(healing("i1", "r5"));
                assertEquals("r5", readHealing(store, "i1").orElseThrow().reason());
                assertEquals(5L, storedEpoch(store, "workflow_healing", "i1"));

                // Deposed leader at epoch 3 tries to clobber -> dropped, doc unchanged, counter bumps.
                epoch.set(3L);
                store.saveHealingActionIntent(healing("i1", "r3-stale"));
                assertEquals("r5", readHealing(store, "i1").orElseThrow().reason(), "stale write must not win");
                assertEquals(5L, storedEpoch(store, "workflow_healing", "i1"));
                assertEquals(1L, store.fencedWriteRejections());

                // New leader at epoch 7 supersedes.
                epoch.set(7L);
                store.saveHealingActionIntent(healing("i1", "r7"));
                assertEquals("r7", readHealing(store, "i1").orElseThrow().reason());
                assertEquals(7L, storedEpoch(store, "workflow_healing", "i1"));
                assertEquals(1L, store.fencedWriteRejections(), "no new rejection on a valid supersede");
            } finally {
                mongoClient.getDatabase(dbName).drop();
            }
        }
    }

    @Test
    @DisplayName("delete fence: deposed leader cannot delete the live leader's recreated doc")
    void deleteFence() {
        Assumptions.assumeTrue(socketAvailable(resolveMongoUri(), 27017), "Mongo is not reachable");
        String dbName = "prexor-fence-it-" + UUID.randomUUID();
        try (var mongoClient = MongoClients.create(resolveMongoUri())) {
            var db = mongoClient.getDatabase(dbName);
            try {
                MongoStateStore store = new MongoStateStore(mongoClient, db);
                store.initialize();
                AtomicLong epoch = new AtomicLong(7L);
                store.setEpochSource(epoch::get);

                store.saveHealingActionIntent(healing("i1", "r7"));
                // Deposed leader (epoch 3) delete must not remove the higher-epoch doc.
                epoch.set(3L);
                store.deleteHealingActionIntent("i1");
                assertTrue(readHealing(store, "i1").isPresent(), "stale delete must be fenced");
                // Current leader (epoch 7) delete removes it.
                epoch.set(7L);
                store.deleteHealingActionIntent("i1");
                assertTrue(readHealing(store, "i1").isEmpty());
            } finally {
                mongoClient.getDatabase(dbName).drop();
            }
        }
    }

    @Test
    @DisplayName("update fence: deployment state transitions are epoch-guarded")
    void updateFence() {
        Assumptions.assumeTrue(socketAvailable(resolveMongoUri(), 27017), "Mongo is not reachable");
        String dbName = "prexor-fence-it-" + UUID.randomUUID();
        try (var mongoClient = MongoClients.create(resolveMongoUri())) {
            var db = mongoClient.getDatabase(dbName);
            try {
                MongoStateStore store = new MongoStateStore(mongoClient, db);
                store.initialize();
                AtomicLong epoch = new AtomicLong(0L);
                store.setEpochSource(epoch::get);

                var created = store.createDeployment(
                        new me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord(
                                0, "grp", 1, "MANUAL", "ROLLING", "PENDING", "tmpl", "cfg", 1, 0, null, null, null));
                int id = created.id();

                epoch.set(5L);
                store.updateDeploymentState(id, "RUNNING");
                assertEquals("RUNNING", store.getDeployment("grp", 1).orElseThrow().state());

                // Deposed leader (epoch 3) cannot regress the FSM.
                epoch.set(3L);
                store.updateDeploymentState(id, "FAILED");
                assertEquals(
                        "RUNNING", store.getDeployment("grp", 1).orElseThrow().state(), "stale FSM write fenced");

                epoch.set(7L);
                store.updateDeploymentState(id, "COMPLETED");
                assertEquals("COMPLETED", store.getDeployment("grp", 1).orElseThrow().state());
            } finally {
                mongoClient.getDatabase(dbName).drop();
            }
        }
    }

    @Test
    @DisplayName("fence disabled (epoch <= 0) and legacy unstamped docs both write freely")
    void disabledAndLegacy() {
        Assumptions.assumeTrue(socketAvailable(resolveMongoUri(), 27017), "Mongo is not reachable");
        String dbName = "prexor-fence-it-" + UUID.randomUUID();
        try (var mongoClient = MongoClients.create(resolveMongoUri())) {
            var db = mongoClient.getDatabase(dbName);
            try {
                MongoStateStore store = new MongoStateStore(mongoClient, db);
                store.initialize();
                AtomicLong epoch = new AtomicLong(0L);
                store.setEpochSource(epoch::get);

                // Fence disabled: repeated writes always win, no ownerEpoch stamped.
                store.saveHealingActionIntent(healing("i2", "a"));
                store.saveHealingActionIntent(healing("i2", "b"));
                assertEquals("b", readHealing(store, "i2").orElseThrow().reason());
                assertEquals(null, storedEpoch(store, "workflow_healing", "i2"), "disabled fence stamps nothing");
                assertEquals(0L, store.fencedWriteRejections());

                // A legacy doc with no ownerEpoch is treated as writable by a fenced write.
                store.database()
                        .getCollection("workflow_healing")
                        .insertOne(new Document("_id", "i4").append("reason", "old"));
                epoch.set(2L);
                store.saveHealingActionIntent(healing("i4", "migrated"));
                assertEquals("migrated", readHealing(store, "i4").orElseThrow().reason());
                assertEquals(2L, storedEpoch(store, "workflow_healing", "i4"));

                // First fenced write to a brand-new id inserts (absent path).
                store.saveHealingActionIntent(healing("i5", "fresh"));
                assertNotNull(readHealing(store, "i5").orElse(null));
                assertEquals(2L, storedEpoch(store, "workflow_healing", "i5"));
                assertFalse(store.fencedWriteRejections() > 0, "no rejections on inserts/legacy writes");
            } finally {
                mongoClient.getDatabase(dbName).drop();
            }
        }
    }

    private static String resolveMongoUri() {
        String env = System.getenv("PREXOR_TEST_MONGO_URI");
        if (env != null && !env.isBlank()) return env;
        String prop = System.getProperty("prexor.test.mongoUri");
        if (prop != null && !prop.isBlank()) return prop;
        return "mongodb://127.0.0.1:27017";
    }

    private static boolean socketAvailable(String endpointUri, int defaultPort) {
        URI uri = URI.create(endpointUri);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception _) {
            return false;
        }
    }
}
