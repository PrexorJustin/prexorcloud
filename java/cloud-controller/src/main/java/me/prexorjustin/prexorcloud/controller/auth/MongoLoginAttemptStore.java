package me.prexorjustin.prexorcloud.controller.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;

/**
 * MongoDB-backed {@link LoginAttemptStore}. One document per username holds the
 * rolling failure counter, the window start, and any active lock; a TTL index on
 * {@code expiresAt} reaps a document once both the failure window and the lock
 * have elapsed.
 *
 * <p>Throttle/lockout state is a durable store rather than leader memory because
 * losing it on a leadership change would reset every attacker's counter — a
 * security regression. {@link #recordFailure} runs as a single atomic
 * aggregation-pipeline {@code findOneAndUpdate} stamped with server time
 * ({@code $$NOW}) so a sliding window can not be raced across concurrent
 * failures, mirroring the {@code INCR}+{@code EXPIRE} the Redis store relied on.
 */
public final class MongoLoginAttemptStore implements LoginAttemptStore {

    private static final String COLLECTION = "login_attempts";
    private static final FindOneAndUpdateOptions UPSERT_AFTER =
            new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER);
    private static final UpdateOptions UPSERT = new UpdateOptions().upsert(true);

    private final MongoCollection<Document> collection;

    public MongoLoginAttemptStore(MongoDatabase db) {
        this.collection = Objects.requireNonNull(db, "db").getCollection(COLLECTION);
        this.collection.createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("login_attempts_ttl"));
    }

    @Override
    public int recordFailure(String username, Duration window) {
        long windowMs = Math.max(1L, window.toMillis());
        // True when the previous window has lapsed (or there is none) → start a fresh window at 1.
        Document isNewWindow = new Document(
                "$or",
                List.of(
                        new Document("$eq", Arrays.asList("$windowStart", null)),
                        new Document(
                                "$lt",
                                List.of("$windowStart", new Document("$subtract", List.of("$$NOW", windowMs))))));
        List<Document> pipeline = List.of(new Document(
                "$set",
                new Document()
                        .append(
                                "failCount",
                                new Document(
                                        "$cond",
                                        List.of(
                                                isNewWindow,
                                                1,
                                                new Document(
                                                        "$add",
                                                        List.of(
                                                                new Document("$ifNull", List.of("$failCount", 0)),
                                                                1)))))
                        .append(
                                "windowStart",
                                new Document(
                                        "$cond",
                                        List.of(
                                                isNewWindow,
                                                "$$NOW",
                                                new Document("$ifNull", List.of("$windowStart", "$$NOW")))))
                        .append(
                                "expiresAt",
                                new Document(
                                        "$max",
                                        List.of(
                                                new Document("$add", List.of("$$NOW", windowMs)),
                                                new Document("$ifNull", List.of("$lockedUntil", "$$NOW")))))));
        Document updated = collection.findOneAndUpdate(Filters.eq("_id", username), pipeline, UPSERT_AFTER);
        if (updated == null) return 1;
        Integer count = updated.getInteger("failCount");
        return count == null ? 1 : count;
    }

    @Override
    public void clear(String username) {
        collection.deleteOne(Filters.eq("_id", username));
    }

    @Override
    public void lockUntil(String username, Instant until) {
        Date untilDate = Date.from(until);
        List<Document> pipeline = List.of(new Document(
                "$set",
                new Document()
                        .append("lockedUntil", untilDate)
                        .append(
                                "expiresAt",
                                new Document(
                                        "$max",
                                        List.of(new Document("$ifNull", List.of("$expiresAt", "$$NOW")), untilDate)))));
        collection.updateOne(Filters.eq("_id", username), pipeline, UPSERT);
    }

    @Override
    public Optional<Instant> lockedUntil(String username) {
        Document doc = collection.find(Filters.eq("_id", username)).first();
        if (doc == null) return Optional.empty();
        Date until = doc.getDate("lockedUntil");
        if (until == null) return Optional.empty();
        Instant untilInstant = until.toInstant();
        if (!untilInstant.isAfter(Instant.now())) return Optional.empty();
        return Optional.of(untilInstant);
    }
}
