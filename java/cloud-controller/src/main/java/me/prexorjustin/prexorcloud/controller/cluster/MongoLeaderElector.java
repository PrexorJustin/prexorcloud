package me.prexorjustin.prexorcloud.controller.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-writer leadership via a fenced MongoDB lease.
 *
 * <p>This is the correctness centerpiece of the single-writer control plane (replaces the
 * Raft-backed {@link ClusterLeaseManager} + the Redis {@code DistributedLeaseManager}). Exactly
 * one controller holds the {@code cluster_leadership} lease at a time; the holder is "the leader"
 * and owns the scheduler, the reconciler, and every daemon gRPC stream. Followers are read-only.
 *
 * <h2>The lease document</h2>
 * A single doc in {@code cluster_leadership}, {@code _id = "leader"}:
 * <pre>{@code {_id:"leader", holder:<controllerUuid|null>, epoch:<long>, renewedAt:<Date>, ttlMs:<long>}}</pre>
 *
 * <h2>Fencing token = monotonic epoch</h2>
 * {@code epoch} increments on every <em>fresh</em> acquisition (a takeover of a vacant/expired
 * lease) and never on a renew. A leader paused past its lease (GC/STW) that wakes up after a
 * successor took over must never act: its renew is filtered on {@code (holder, epoch)} and fails,
 * and any in-flight daemon command it issued carries the now-stale epoch (daemon-side fence, Phase
 * 3). The epoch is the only thing callers need to stamp on fenced writes/commands.
 *
 * <h2>Server-authoritative time</h2>
 * Lease expiry is decided against MongoDB <em>server</em> time ({@code $$NOW}) compared with the
 * server-stamped {@code renewedAt}, so controller↔controller clock skew is removed from the safety
 * argument — one clock (Mongo's) decides liveness for everyone. Acquire/renew run as a single
 * atomic aggregation-pipeline {@code findOneAndUpdate} so the read-decide-write is indivisible.
 *
 * <h2>Local liveness guard</h2>
 * Independently, the leader stops acting if it has not <em>confirmed</em> a renew within
 * {@code ttlMs - safetyMargin} of <em>local monotonic</em> time. This is the GC/partition guard:
 * if this controller cannot reach Mongo it cannot know whether a successor took over, so it must
 * fence itself. {@link #isLeader()} folds this guard in — it returns {@code false} the instant the
 * guard trips, before the next poll runs.
 */
public final class MongoLeaderElector implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MongoLeaderElector.class);

    /** Collection + singleton doc id for the leadership lease. */
    public static final String COLLECTION = "cluster_leadership";

    public static final String LEASE_ID = "leader";

    /** Sentinel "epoch zero" instant used to seed a vacant lease as already-expired. */
    private static final java.util.Date EPOCH_ZERO = new java.util.Date(0L);

    /**
     * Transition hook fired on leadership change. {@code onAcquired} is the seam the
     * resync-from-scratch path (Phase 2 observation phase + leader-memory rehydrate) and the
     * change-stream layer (Phase 5) hang off of; {@code onLost} tears that state down. Both run
     * on the elector's single poll thread, so implementations must not block for long.
     */
    public interface LeadershipListener {
        /** Called once per fresh acquisition, with the new (higher) epoch. */
        default void onAcquired(long epoch) {}

        /** Called when leadership is definitively lost (renew rejected) or released. */
        default void onLost() {}

        LeadershipListener NOOP = new LeadershipListener() {};
    }

    /**
     * Lease timing. {@code ttl} is the lease lifetime; {@code renewInterval} how often the holder
     * refreshes it; {@code safetyMargin} the local-clock cushion subtracted from the ttl before the
     * leader self-fences. Invariant (validated): {@code renewInterval < ttl - safetyMargin} so a
     * single missed renew never trips the guard, and {@code safetyMargin > 0 < ttl}.
     */
    public record LeaseSettings(Duration ttl, Duration renewInterval, Duration safetyMargin) {
        public LeaseSettings {
            if (ttl == null || renewInterval == null || safetyMargin == null) {
                throw new IllegalArgumentException("lease durations must be non-null");
            }
            if (safetyMargin.isNegative() || safetyMargin.isZero() || safetyMargin.compareTo(ttl) >= 0) {
                throw new IllegalArgumentException(
                        "safetyMargin must be > 0 and < ttl (ttl=" + ttl + ", margin=" + safetyMargin + ")");
            }
            if (renewInterval.isNegative() || renewInterval.isZero()) {
                throw new IllegalArgumentException("renewInterval must be > 0");
            }
            Duration guard = ttl.minus(safetyMargin);
            if (renewInterval.compareTo(guard) >= 0) {
                throw new IllegalArgumentException(
                        "renewInterval (" + renewInterval + ") must be < ttl-safetyMargin (" + guard + ")");
            }
        }

        /** Defaults: 15s ttl, 3s renew, 5s margin → a 10s local guard window (~3 renews fit). */
        public static LeaseSettings defaults() {
            return new LeaseSettings(Duration.ofSeconds(15), Duration.ofSeconds(3), Duration.ofSeconds(5));
        }

        long guardNanos() {
            return ttl.minus(safetyMargin).toNanos();
        }
    }

    /** Snapshot of the leadership doc as read from Mongo. */
    public record LeaseRecord(String holder, long epoch, Instant renewedAt, long ttlMs) {}

    private final MongoCollection<Document> lease; // majority write + majority read (update path)
    private final MongoCollection<Document> leaseLinearizable; // linearizable read (pure reads)
    private final String holderId;
    private final LeaseSettings settings;
    private final LeadershipListener listener;
    private final LongSupplier nanoClock;

    // --- mutable leadership state (mutated only on the poll thread; read everywhere) ---
    private volatile boolean leader = false;
    private volatile long epoch = 0L;
    private volatile long lastRenewNanos = 0L;

    // --- observability counters ---
    private final AtomicLong transitions = new AtomicLong();

    private ScheduledExecutorService poller;

    public MongoLeaderElector(MongoDatabase db, String holderId, LeaseSettings settings) {
        this(db, holderId, settings, LeadershipListener.NOOP, System::nanoTime);
    }

    public MongoLeaderElector(
            MongoDatabase db,
            String holderId,
            LeaseSettings settings,
            LeadershipListener listener,
            LongSupplier nanoClock) {
        if (holderId == null || holderId.isBlank()) {
            throw new IllegalArgumentException("holderId must be set");
        }
        this.holderId = holderId;
        this.settings = settings;
        this.listener = listener == null ? LeadershipListener.NOOP : listener;
        this.nanoClock = nanoClock;
        // Lease writes MUST be majority-acked so a renew/acquire survives a Mongo primary failover —
        // a w:1 ack that the new primary never saw would let two controllers believe they hold it.
        this.lease = db.getCollection(COLLECTION)
                .withWriteConcern(WriteConcern.MAJORITY)
                .withReadConcern(ReadConcern.MAJORITY);
        // Pure reads of the leadership state use linearizable read concern (read your own + all
        // acked writes, against the primary) so a follower/observer never sees a stale leader.
        this.leaseLinearizable = db.getCollection(COLLECTION).withReadConcern(ReadConcern.LINEARIZABLE);
        ensureSeedDocument();
    }

    /**
     * Insert the vacant lease doc if absent. Seeded as already-expired ({@code renewedAt=epoch 0},
     * {@code ttlMs=0}) so the first acquire takes it. Concurrent seeds race on the unique {@code _id};
     * the loser's duplicate-key error is swallowed.
     */
    private void ensureSeedDocument() {
        try {
            lease.insertOne(new Document("_id", LEASE_ID)
                    .append("holder", null)
                    .append("epoch", 0L)
                    .append("renewedAt", EPOCH_ZERO)
                    .append("ttlMs", 0L));
            logger.info("Seeded vacant leadership lease doc");
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
            // already seeded by another controller — expected
        }
    }

    // --- public API ------------------------------------------------------------------------

    /**
     * True iff this controller currently holds the lease <em>and</em> the local liveness guard has
     * not tripped. The guard fold makes this the load-bearing fence: callers gate every scheduler
     * tick / daemon command on {@code isLeader()} and stop the instant we may have been deposed.
     */
    public boolean isLeader() {
        return leader && localGuardOk();
    }

    /** The epoch this controller acquired the lease at. Only meaningful while {@link #isLeader()}. */
    public long currentEpoch() {
        return epoch;
    }

    public String holderId() {
        return holderId;
    }

    /** Count of times this controller transitioned follower→leader (observability). */
    public long leadershipTransitions() {
        return transitions.get();
    }

    /** Local-clock age (ms) since the last confirmed renew; proximity to the step-down guard. */
    public long renewAgeMillis() {
        if (!leader) {
            return -1L;
        }
        return TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - lastRenewNanos);
    }

    /** Read the current lease doc (linearizable). Empty only if the seed somehow vanished. */
    public Optional<LeaseRecord> readLease() {
        Document doc = leaseLinearizable.find(Filters.eq("_id", LEASE_ID)).first();
        if (doc == null) {
            return Optional.empty();
        }
        String holder = doc.getString("holder");
        long ep = doc.get("epoch") == null ? 0L : ((Number) doc.get("epoch")).longValue();
        java.util.Date renewed = doc.getDate("renewedAt");
        long ttlMs = doc.get("ttlMs") == null ? 0L : ((Number) doc.get("ttlMs")).longValue();
        return Optional.of(new LeaseRecord(holder, ep, renewed == null ? Instant.EPOCH : renewed.toInstant(), ttlMs));
    }

    /** Begin the background acquire/renew loop. Idempotent. */
    public synchronized void start() {
        if (poller != null) {
            return;
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mongo-leader-elector");
            t.setDaemon(true);
            return t;
        });
        long periodMs = settings.renewInterval().toMillis();
        poller.scheduleWithFixedDelay(this::pollQuietly, 0, periodMs, TimeUnit.MILLISECONDS);
        logger.info(
                "Leadership elector started (holder={}, ttl={}, renew={}, margin={})",
                holderId,
                settings.ttl(),
                settings.renewInterval(),
                settings.safetyMargin());
    }

    private void pollQuietly() {
        try {
            poll();
        } catch (RuntimeException e) {
            logger.warn("Leadership poll failed: {}", e.toString());
        }
    }

    /**
     * One acquire/renew tick. Exposed (and {@code synchronized}) so tests can drive it
     * deterministically without the scheduler. Returns {@code true} iff this controller holds the
     * lease after the tick (server-confirmed; ignores the local guard).
     */
    public synchronized boolean poll() {
        if (!leader) {
            return tryAcquireTick();
        }
        return renewTick();
    }

    @Override
    public synchronized void close() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        // Best-effort release so a successor can take over immediately rather than waiting a full ttl.
        if (leader) {
            try {
                releaseLease();
            } catch (RuntimeException e) {
                logger.debug("Lease release on close failed (will expire): {}", e.toString());
            }
            demote();
        }
    }

    // --- internals -------------------------------------------------------------------------

    private boolean localGuardOk() {
        return (nanoClock.getAsLong() - lastRenewNanos) <= settings.guardNanos();
    }

    private boolean tryAcquireTick() {
        Document after = lease.findOneAndUpdate(
                Filters.eq("_id", LEASE_ID),
                acquirePipeline(),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (after == null) {
            // Seed vanished under us — reseed and let the next tick retry.
            ensureSeedDocument();
            return false;
        }
        if (holderId.equals(after.getString("holder"))) {
            long newEpoch = ((Number) after.get("epoch")).longValue();
            promote(newEpoch);
            return true;
        }
        return false;
    }

    private boolean renewTick() {
        boolean renewed;
        try {
            Document after = lease.findOneAndUpdate(
                    Filters.and(
                            Filters.eq("_id", LEASE_ID), Filters.eq("holder", holderId), Filters.eq("epoch", epoch)),
                    renewPipeline(),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            renewed = after != null;
        } catch (MongoCommandException | MongoWriteException e) {
            // Mongo reachable but rejected the op — treat as a hard failure path below.
            throw e;
        } catch (RuntimeException mongoUnreachable) {
            // We don't know if a successor took over. Don't advance the renew clock; the local
            // guard fences isLeader() if this persists. Demote eagerly once the guard has tripped.
            logger.warn(
                    "Lease renew unreachable for holder={} epoch={}: {}", holderId, epoch, mongoUnreachable.toString());
            if (!localGuardOk()) {
                demote();
            }
            return false;
        }
        if (renewed) {
            lastRenewNanos = nanoClock.getAsLong();
            return true;
        }
        // Filter didn't match: the lease was taken by another holder or bumped to a new epoch.
        logger.warn("Lease renew rejected — lost leadership (was holder={} epoch={})", holderId, epoch);
        demote();
        return false;
    }

    private void releaseLease() {
        // Mark the lease vacant (holder=null, expired) but keep epoch monotonic so the next acquirer
        // still bumps it. Guarded on (holder, epoch) so we never clobber a successor's lease.
        lease.findOneAndUpdate(
                Filters.and(Filters.eq("_id", LEASE_ID), Filters.eq("holder", holderId), Filters.eq("epoch", epoch)),
                List.of(new Document(
                        "$set",
                        new Document("holder", null)
                                .append("renewedAt", EPOCH_ZERO)
                                .append("ttlMs", 0L))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    private void promote(long newEpoch) {
        boolean fresh = !leader || newEpoch != epoch;
        epoch = newEpoch;
        lastRenewNanos = nanoClock.getAsLong();
        leader = true;
        if (fresh) {
            transitions.incrementAndGet();
            logger.info("Acquired leadership (holder={}, epoch={})", holderId, newEpoch);
            try {
                listener.onAcquired(newEpoch);
            } catch (RuntimeException e) {
                logger.error("Leadership onAcquired listener threw", e);
            }
        }
    }

    private void demote() {
        if (!leader) {
            return;
        }
        leader = false;
        logger.info("Relinquished leadership (holder={}, epoch={})", holderId, epoch);
        try {
            listener.onLost();
        } catch (RuntimeException e) {
            logger.error("Leadership onLost listener threw", e);
        }
    }

    /**
     * Aggregation pipeline for acquire: take the lease iff it is vacant (holder null) or expired
     * (server time), or renew-in-place iff we already hold a still-valid lease. Bumps epoch only on
     * a fresh take. A no-op (leaves the doc untouched) when another holder's lease is still valid.
     */
    private List<Bson> acquirePipeline() {
        long ttlMs = settings.ttl().toMillis();
        // $$NOW - renewedAt (ms) >= ttlMs  ->  expired (server-authoritative)
        Document expired = new Document(
                "$gte",
                List.of(
                        new Document("$subtract", List.of("$$NOW", ifNull("$renewedAt", EPOCH_ZERO))),
                        ifNullNum("$ttlMs", 0L)));
        Document isMine = new Document("$eq", List.of("$holder", holderId));
        // List.of rejects null elements, so the "$holder == null" comparison uses a null-tolerant list.
        Document holderIsNull = new Document("$eq", java.util.Arrays.asList("$holder", null));
        Document vacant = new Document("$or", List.of(holderIsNull, "$__expired"));
        Document renewInPlace = new Document("$and", List.of("$__isMine", new Document("$not", List.of("$__expired"))));
        Document take = new Document("$or", List.of("$__vacant", "$__renewInPlace"));

        return List.of(
                new Document("$set", new Document("__expired", expired).append("__isMine", isMine)),
                new Document("$set", new Document("__vacant", vacant).append("__renewInPlace", renewInPlace)),
                new Document("$set", new Document("__take", take)),
                new Document(
                        "$set",
                        new Document()
                                .append("holder", cond("$__take", holderId, "$holder"))
                                .append("renewedAt", cond("$__take", "$$NOW", "$renewedAt"))
                                .append("ttlMs", cond("$__take", ttlMs, ifNullNum("$ttlMs", 0L)))
                                // Fresh take (vacant) bumps epoch; renew-in-place and no-op keep it.
                                .append(
                                        "epoch",
                                        new Document(
                                                "$cond",
                                                List.of(
                                                        "$__vacant",
                                                        new Document("$add", List.of(ifNullNum("$epoch", 0L), 1L)),
                                                        ifNullNum("$epoch", 0L))))),
                new Document("$unset", List.of("__expired", "__isMine", "__vacant", "__renewInPlace", "__take")));
    }

    /** Renew pipeline: refresh the server timestamp + ttl only. Never touches holder or epoch. */
    private List<Bson> renewPipeline() {
        long ttlMs = settings.ttl().toMillis();
        return List.of(new Document("$set", new Document("renewedAt", "$$NOW").append("ttlMs", ttlMs)));
    }

    private static Document cond(Object ifExpr, Object thenExpr, Object elseExpr) {
        return new Document("$cond", List.of(ifExpr, thenExpr, elseExpr));
    }

    private static Document ifNull(String field, Object fallback) {
        return new Document("$ifNull", List.of(field, fallback));
    }

    private static Document ifNullNum(String field, long fallback) {
        return new Document("$ifNull", List.of(field, fallback));
    }
}
