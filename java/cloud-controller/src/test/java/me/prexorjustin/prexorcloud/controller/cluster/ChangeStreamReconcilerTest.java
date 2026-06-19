package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for the Phase-5 reactive change-stream reconcile layer. Change streams need a
 * real replica-set Mongo, so these boot a {@link MongoDBContainer} (single-node RS — the deployed
 * topology) or attach to an external RS via {@code PREXOR_TEST_MONGO_URI}; they self-skip where
 * neither is available. CI runs them.
 *
 * <p>Covers: a change to a watched collection triggers a reconcile; a change to an unwatched
 * collection does not; and a watcher resumed from a stored token replays changes made while it was
 * down (the resume-token path the failover handling sits on).
 */
final class ChangeStreamReconcilerTest {

    private static MongoDBContainer mongo;
    private static MongoClient client;

    @BeforeAll
    static void up() {
        String externalUri = System.getenv("PREXOR_TEST_MONGO_URI");
        if (externalUri == null || externalUri.isBlank()) {
            externalUri = System.getProperty("prexor.test.mongoUri");
        }
        if (externalUri != null && !externalUri.isBlank()) {
            client = MongoClients.create(externalUri);
            return;
        }
        assumeTrue(dockerAvailable(), "Docker not available — skipping change-stream integration tests");
        mongo = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
        mongo.start();
        client = MongoClients.create(mongo.getConnectionString());
    }

    @AfterAll
    static void down() {
        if (client != null) {
            client.close();
        }
        if (mongo != null) {
            mongo.stop();
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private MongoDatabase freshDb() {
        return client.getDatabase(
                "cs_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    private static ChangeStreamReconciler watcher(MongoDatabase db, Set<String> colls, Runnable onChange) {
        // Short awaits + backoff so the tests are snappy.
        return new ChangeStreamReconciler(db, colls, onChange, 200L, 200L, System::nanoTime);
    }

    @Test
    void triggersReconcileOnWatchedCollectionChange() throws Exception {
        MongoDatabase db = freshDb();
        CountDownLatch latch = new CountDownLatch(1);
        ChangeStreamReconciler r = watcher(db, Set.of("groups"), latch::countDown);
        try {
            r.start();
            awaitTrue(() -> r.streamOpens() >= 1, 5_000); // cursor must be open before we write
            db.getCollection("groups").insertOne(new Document("_id", "g1").append("v", 1));
            assertTrue(latch.await(5, TimeUnit.SECONDS), "a change to a watched collection must trigger a reconcile");
            assertTrue(r.changesObserved() >= 1, "the change must be counted");
        } finally {
            r.stop();
        }
    }

    @Test
    void ignoresChangesToUnwatchedCollections() throws Exception {
        MongoDatabase db = freshDb();
        AtomicInteger count = new AtomicInteger();
        ChangeStreamReconciler r = watcher(db, Set.of("groups"), count::incrementAndGet);
        try {
            r.start();
            awaitTrue(() -> r.streamOpens() >= 1, 5_000);
            // Unwatched write first, then a watched write as a fence: change events are oplog-ordered,
            // so once the watched trigger fires the unwatched op has already passed the filter point.
            db.getCollection("audit_log").insertOne(new Document("_id", "a1"));
            db.getCollection("groups").insertOne(new Document("_id", "g1"));
            awaitTrue(() -> count.get() >= 1, 5_000);
            Thread.sleep(300); // let any erroneous extra trigger surface
            assertEquals(1, count.get(), "only the watched-collection change may trigger a reconcile");
        } finally {
            r.stop();
        }
    }

    @Test
    void resumesFromStoredToken() throws Exception {
        MongoDatabase db = freshDb();

        // Watcher #1: observe one change, capture its resume token, stop.
        CountDownLatch latch1 = new CountDownLatch(1);
        ChangeStreamReconciler r1 = watcher(db, Set.of("groups"), latch1::countDown);
        r1.start();
        awaitTrue(() -> r1.streamOpens() >= 1, 5_000);
        db.getCollection("groups").insertOne(new Document("_id", "g1"));
        assertTrue(latch1.await(5, TimeUnit.SECONDS));
        r1.stop();
        BsonDocument token = r1.currentResumeToken();
        assertNotNull(token, "watcher must track a resume token after an event");

        // A change happens while nothing is watching.
        db.getCollection("groups").insertOne(new Document("_id", "g2"));

        // Watcher #2: seeded with the stored token, must resume and pick up the change made while down.
        CountDownLatch latch2 = new CountDownLatch(1);
        ChangeStreamReconciler r2 = watcher(db, Set.of("groups"), latch2::countDown);
        r2.seedResumeToken(token);
        try {
            r2.start();
            assertTrue(
                    latch2.await(5, TimeUnit.SECONDS),
                    "a watcher resumed from a stored token must replay changes made while it was down");
        } finally {
            r2.stop();
        }
    }

    private static void awaitTrue(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadlineNanos) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        if (!cond.getAsBoolean()) {
            throw new AssertionError("condition not met within " + timeoutMs + "ms");
        }
    }
}
