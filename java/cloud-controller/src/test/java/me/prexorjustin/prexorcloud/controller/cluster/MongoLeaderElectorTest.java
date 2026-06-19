package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import me.prexorjustin.prexorcloud.controller.cluster.MongoLeaderElector.LeaseSettings;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for the fenced Mongo leadership lease. These need a real replica-set Mongo
 * (atomic {@code findOneAndUpdate}, {@code $$NOW} server time, linearizable reads), so they boot a
 * {@link MongoDBContainer} (which auto-initializes a single-node RS — the deployed topology). They
 * self-skip where Docker is unavailable; CI runs them.
 *
 * <p>Covers the four Phase-1 gates: single-winner under concurrent acquire, monotonic epoch,
 * GC-pause-past-lease fenced via renew rejection, and step-down on renew failure (both the
 * server-side rejection and the local liveness guard).
 */
final class MongoLeaderElectorTest {

    private static MongoDBContainer mongo;
    private static MongoClient client;

    @BeforeAll
    static void up() {
        // Escape hatch: point at an already-running replica-set Mongo (e.g. a local mongod RS where
        // Docker is unavailable) via PREXOR_TEST_MONGO_URI. Otherwise boot an ephemeral container.
        String externalUri = System.getenv("PREXOR_TEST_MONGO_URI");
        if (externalUri == null || externalUri.isBlank()) {
            externalUri = System.getProperty("prexor.test.mongoUri");
        }
        if (externalUri != null && !externalUri.isBlank()) {
            client = MongoClients.create(externalUri);
            return;
        }
        assumeTrue(dockerAvailable(), "Docker not available — skipping Mongo lease integration tests");
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

    /** Each test gets a private database so they never share the singleton lease doc. */
    private MongoDatabase freshDb() {
        return client.getDatabase(
                "lease_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    private static LeaseSettings shortLease() {
        // ttl 1s, renew 200ms, margin 300ms -> 700ms local guard window. Fast enough for expiry tests.
        return new LeaseSettings(Duration.ofMillis(1000), Duration.ofMillis(200), Duration.ofMillis(300));
    }

    // 1. Single-winner under concurrent acquire ------------------------------------------------

    @Test
    void singleWinnerUnderConcurrentAcquire() throws Exception {
        MongoDatabase db = freshDb();
        int n = 8;
        List<MongoLeaderElector> electors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < n; i++) {
            electors.add(new MongoLeaderElector(db, "ctrl-" + i, LeaseSettings.defaults()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Boolean>> results = new CopyOnWriteArrayList<>();
            for (MongoLeaderElector e : electors) {
                results.add(pool.submit(e::poll));
            }
            int winners = 0;
            for (Future<Boolean> f : results) {
                if (f.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners, "exactly one elector must win the concurrent acquire");
        } finally {
            pool.shutdownNow();
        }

        long leaders = electors.stream().filter(MongoLeaderElector::isLeader).count();
        assertEquals(1, leaders, "exactly one elector reports leadership");

        MongoLeaderElector leader = electors.stream()
                .filter(MongoLeaderElector::isLeader)
                .findFirst()
                .orElseThrow();
        var rec = leader.readLease().orElseThrow();
        assertEquals(leader.holderId(), rec.holder());
        assertEquals(1L, rec.epoch(), "first acquisition is epoch 1");

        electors.forEach(MongoLeaderElector::close);
    }

    // 2. Monotonic epoch across hand-offs -------------------------------------------------------

    @Test
    void epochIncreasesMonotonicallyAcrossHandoffs() {
        MongoDatabase db = freshDb();
        var a = new MongoLeaderElector(db, "ctrl-a", LeaseSettings.defaults());
        var b = new MongoLeaderElector(db, "ctrl-b", LeaseSettings.defaults());
        var c = new MongoLeaderElector(db, "ctrl-c", LeaseSettings.defaults());

        assertTrue(a.poll());
        long e1 = a.currentEpoch();
        assertEquals(1L, e1);
        a.close(); // releases the lease (vacant, epoch preserved)

        assertTrue(b.poll());
        long e2 = b.currentEpoch();
        b.close();

        assertTrue(c.poll());
        long e3 = c.currentEpoch();
        c.close();

        assertTrue(e2 > e1, "epoch must strictly increase on each fresh acquisition (e2=" + e2 + " e1=" + e1 + ")");
        assertTrue(e3 > e2, "epoch must strictly increase (e3=" + e3 + " e2=" + e2 + ")");
    }

    // 3. GC pause past the lease is fenced via renew rejection ----------------------------------

    @Test
    void gcPausePastLeaseIsFenced() throws Exception {
        MongoDatabase db = freshDb();
        var capturedA = new Captured();
        var a = new MongoLeaderElector(db, "ctrl-a", shortLease(), capturedA, System::nanoTime);
        var b = new MongoLeaderElector(db, "ctrl-b", shortLease());

        assertTrue(a.poll(), "A acquires first");
        assertEquals(1L, a.currentEpoch());

        // Simulate A paused (GC/STW) past its lease: do not renew. Sleep beyond ttl so it expires.
        Thread.sleep(1300);

        // B observes the expired lease and takes over with a higher epoch.
        assertTrue(b.poll(), "B takes over the expired lease");
        assertTrue(b.isLeader());
        assertTrue(
                b.currentEpoch() > a.currentEpoch(),
                "successor epoch must exceed the deposed leader's (" + b.currentEpoch() + " > " + a.currentEpoch()
                        + ")");

        // A "wakes up" and tries to renew at its stale epoch -> rejected -> it steps down.
        assertFalse(a.poll(), "A's renew at the stale epoch must be rejected");
        assertFalse(a.isLeader(), "the deposed leader must not believe it is leader");
        assertEquals(1, capturedA.lost.get(), "A must fire onLost exactly once");

        a.close();
        b.close();
    }

    // 4a. Step-down on renew failure — server-side rejection ------------------------------------

    @Test
    void stepsDownWhenLeaseTakenByAnother() {
        MongoDatabase db = freshDb();
        var captured = new Captured();
        var a = new MongoLeaderElector(db, "ctrl-a", LeaseSettings.defaults(), captured, System::nanoTime);

        assertTrue(a.poll());
        assertTrue(a.isLeader());

        // Externally overwrite the lease holder (as a successor would), invalidating A's (holder,epoch).
        db.getCollection(MongoLeaderElector.COLLECTION)
                .updateOne(
                        new org.bson.Document("_id", MongoLeaderElector.LEASE_ID),
                        new org.bson.Document("$set", new org.bson.Document("holder", "ctrl-z").append("epoch", 99L)));

        assertFalse(a.poll(), "renew must fail once the lease was taken");
        assertFalse(a.isLeader());
        assertEquals(1, captured.lost.get());

        a.close();
    }

    // 4b. Step-down on renew failure — local liveness guard (deterministic, no Mongo change) ----

    @Test
    void localGuardFencesLeaderThatCannotConfirmRenew() {
        MongoDatabase db = freshDb();
        AtomicLong fakeNanos = new AtomicLong(1_000_000_000L);
        LongSupplier clock = fakeNanos::get;
        var settings = LeaseSettings.defaults(); // 15s ttl, 5s margin -> 10s guard window
        var a = new MongoLeaderElector(db, "ctrl-a", settings, MongoLeaderElector.LeadershipListener.NOOP, clock);

        assertTrue(a.poll());
        assertTrue(a.isLeader(), "leader immediately after acquire");

        // Still leader just inside the guard window.
        fakeNanos.addAndGet(settings.guardNanos() - 1);
        assertTrue(a.isLeader(), "within the guard window the leader still acts");

        // Advance just past ttl - safetyMargin without a confirmed renew: the leader must self-fence,
        // even though Mongo still records it as the holder (no server-side change happened).
        fakeNanos.addAndGet(2);
        assertFalse(a.isLeader(), "past the guard window the leader fences itself");
        assertEquals(
                "ctrl-a",
                a.readLease().orElseThrow().holder(),
                "Mongo still shows A as holder — the fence is purely local-clock based");

        a.close();
    }

    private static final class Captured implements MongoLeaderElector.LeadershipListener {
        final List<Long> acquired = new CopyOnWriteArrayList<>();
        final AtomicInteger lost = new AtomicInteger();

        @Override
        public void onAcquired(long epoch) {
            acquired.add(epoch);
        }

        @Override
        public void onLost() {
            lost.incrementAndGet();
        }
    }
}
