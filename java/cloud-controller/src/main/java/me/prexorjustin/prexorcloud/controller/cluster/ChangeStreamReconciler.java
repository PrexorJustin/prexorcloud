package me.prexorjustin.prexorcloud.controller.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.mongodb.MongoException;
import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reactive reconcile via MongoDB change streams (Phase 5) — the Kubernetes informer layered on top of
 * the periodic resync.
 *
 * <p>This watches the desired-state / workflow-intent collections and, on any change, fires a
 * reconcile trigger so the leader reacts immediately instead of waiting for the next periodic
 * scheduler tick. It is <b>strictly additive over the periodic floor</b> (the leader-gated
 * {@code Scheduler.evaluate()} that already reconciles desired state every few seconds regardless):
 *
 * <ul>
 *   <li><b>Hard invariant:</b> if the change stream lags, disconnects, or errors, the periodic
 *       reconcile still catches everything. The stream can only ever <em>reduce latency</em>, never
 *       cause incorrectness — it never becomes the sole driver.</li>
 *   <li>The trigger ({@code onChange}) is expected to be idempotent and cheap to over-call (it just
 *       schedules a reconcile); a burst of change events collapses into a small number of ticks.</li>
 * </ul>
 *
 * <h2>Lifecycle = leadership</h2>
 * Started on {@code onAcquired} and stopped on {@code onLost} (see {@code MongoLeaderElector
 * .LeadershipListener}). Only the leader watches — followers do not reconcile. On a fresh acquisition
 * the watcher opens from "now"; the gap (changes that happened while this controller was a follower)
 * is covered by the leader's resync-on-takeover and the periodic floor, so opening from now is safe.
 *
 * <h2>Resumption + stale-token fallback</h2>
 * The latest resume token is tracked in memory (refreshed from the post-batch resume token even while
 * idle). On a transient stream error the cursor is reopened from that token. If the token can no
 * longer be resumed — the oplog rolled past it ({@code ChangeStreamHistoryLost}/fatal) — the watcher
 * drops the token, fires a full-resync trigger, and reopens from now. Either way the periodic floor is
 * the backstop, so a stale token is a latency event, not a correctness event.
 */
public final class ChangeStreamReconciler implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ChangeStreamReconciler.class);

    /** Mongo server error codes meaning the change stream cannot be resumed from our token. */
    private static final int CHANGE_STREAM_HISTORY_LOST = 286;

    private static final int CHANGE_STREAM_FATAL_ERROR = 280;

    private final MongoDatabase db;
    private final Set<String> collections;
    private final List<Bson> pipeline;
    private final Runnable onChange;
    private final long maxAwaitMillis;
    private final long reopenBackoffMillis;
    private final LongSupplier nanoClock;

    private volatile BsonDocument resumeToken;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    // --- observability ---
    private final AtomicLong changesObserved = new AtomicLong();
    private final AtomicLong fullResyncs = new AtomicLong();
    private final AtomicLong streamOpens = new AtomicLong();
    private volatile long lastEventNanos = 0L;

    public ChangeStreamReconciler(MongoDatabase db, Set<String> collections, Runnable onChange) {
        this(db, collections, onChange, 500L, 1000L, System::nanoTime);
    }

    ChangeStreamReconciler(
            MongoDatabase db,
            Set<String> collections,
            Runnable onChange,
            long maxAwaitMillis,
            long reopenBackoffMillis,
            LongSupplier nanoClock) {
        if (collections == null || collections.isEmpty()) {
            throw new IllegalArgumentException("must watch at least one collection");
        }
        this.db = db;
        this.collections = Set.copyOf(collections);
        // Database-level change stream filtered to the watched collections (one cursor, not N).
        this.pipeline = List.of(Aggregates.match(Filters.in("ns.coll", new ArrayList<>(this.collections))));
        this.onChange = onChange;
        this.maxAwaitMillis = maxAwaitMillis;
        this.reopenBackoffMillis = reopenBackoffMillis;
        this.nanoClock = nanoClock;
    }

    /** Begin watching on a daemon thread. Idempotent — a second call while running is a no-op. */
    public synchronized void start() {
        if (running.getAndSet(true)) {
            return;
        }
        worker = new Thread(this::runLoop, "change-stream-reconciler");
        worker.setDaemon(true);
        worker.start();
        logger.info("Change-stream reconcile watcher starting on {}", collections);
    }

    /** Stop watching and wait (bounded) for the worker to exit. Idempotent. */
    public void stop() {
        Thread w;
        synchronized (this) {
            if (!running.getAndSet(false)) {
                return;
            }
            w = worker;
            worker = null;
        }
        if (w != null) {
            w.interrupt(); // wake a tryNext() long-poll promptly
            try {
                w.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    // --- internals -------------------------------------------------------------------------

    private void runLoop() {
        while (running.get()) {
            try (MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor = openCursor()) {
                streamOpens.incrementAndGet();
                logger.info(
                        "Change-stream reconcile watcher open (collections={}, resuming={})",
                        collections,
                        resumeToken != null);
                pump(cursor);
            } catch (RuntimeException e) {
                if (!running.get()) {
                    break; // stop() in flight — expected
                }
                if (isNonResumable(e)) {
                    logger.warn(
                            "Change stream non-resumable (token stale / oplog rolled): {} — full resync, reopening from now",
                            e.toString());
                    resumeToken = null;
                    fullResyncs.incrementAndGet();
                    fire("full-resync after non-resumable stream error");
                } else {
                    logger.warn("Change stream errored, will reopen from last token: {}", e.toString());
                }
                backoff();
            }
        }
        logger.info("Change-stream reconcile watcher stopped");
    }

    /** Drain the cursor, triggering a reconcile per change and keeping the resume token fresh. */
    private void pump(MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor) {
        while (running.get()) {
            ChangeStreamDocument<Document> change = cursor.tryNext(); // long-polls up to maxAwaitMillis
            if (change != null) {
                resumeToken = change.getResumeToken();
                lastEventNanos = nanoClock.getAsLong();
                changesObserved.incrementAndGet();
                fire("change " + change.getOperationType() + " on "
                        + (change.getNamespace() == null
                                ? "?"
                                : change.getNamespace().getCollectionName()));
            } else {
                // No event this poll — advance to the post-batch resume token so a later reopen
                // resumes near "now" rather than replaying from the last actual change.
                BsonDocument postBatch = cursor.getResumeToken();
                if (postBatch != null) {
                    resumeToken = postBatch;
                }
            }
        }
    }

    private MongoChangeStreamCursor<ChangeStreamDocument<Document>> openCursor() {
        ChangeStreamIterable<Document> stream = db.watch(pipeline).maxAwaitTime(maxAwaitMillis, TimeUnit.MILLISECONDS);
        BsonDocument token = resumeToken;
        if (token != null) {
            // startAfter (not resumeAfter) so we also resume cleanly after an invalidate event.
            stream = stream.startAfter(token);
        }
        return stream.cursor();
    }

    private void fire(String why) {
        try {
            onChange.run();
        } catch (RuntimeException e) {
            logger.warn("Change-stream reconcile trigger threw ({}): {}", why, e.toString());
        }
    }

    private void backoff() {
        try {
            Thread.sleep(reopenBackoffMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether the error means our resume token is gone for good (vs a transient blip we retry). */
    static boolean isNonResumable(Throwable t) {
        if (t instanceof MongoException me) {
            int code = me.getCode();
            if (code == CHANGE_STREAM_HISTORY_LOST || code == CHANGE_STREAM_FATAL_ERROR) {
                return true;
            }
            return me.hasErrorLabel("NonResumableChangeStreamError");
        }
        return false;
    }

    // --- observability / test seams --------------------------------------------------------

    public boolean running() {
        return running.get();
    }

    /** Count of change events observed since construction. */
    public long changesObserved() {
        return changesObserved.get();
    }

    /** Count of full resyncs forced by a non-resumable stream error (stale token / rolled oplog). */
    public long fullResyncs() {
        return fullResyncs.get();
    }

    /** Count of times the change-stream cursor was (re)opened. */
    public long streamOpens() {
        return streamOpens.get();
    }

    /** Local-clock age (ms) since the last observed change event; -1 if none seen yet. */
    public long lastEventAgeMillis() {
        long at = lastEventNanos;
        if (at == 0L) {
            return -1L;
        }
        return TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - at);
    }

    /** The most recent resume token tracked (post-batch or last event). Null before the first open. */
    public BsonDocument currentResumeToken() {
        return resumeToken;
    }

    /** Test/bootstrap seam: resume from a previously-captured token on the next open. Call before start. */
    void seedResumeToken(BsonDocument token) {
        this.resumeToken = token;
    }
}
