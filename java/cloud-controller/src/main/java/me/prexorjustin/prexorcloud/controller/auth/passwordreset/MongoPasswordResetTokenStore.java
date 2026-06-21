package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

/**
 * MongoDB-backed {@link PasswordResetTokenStore}. Tokens are single-use:
 * {@link #take(String)} is a {@code findOneAndDelete} guarded on a non-expired
 * {@code expiresAt}, the atomic analogue of the Redis {@code GETDEL}, so a token
 * can not be consumed twice nor replayed after expiry. A TTL index reaps spent
 * or abandoned tokens. Durable (not leader memory) so an in-flight reset survives
 * a controller restart between request and completion.
 */
public final class MongoPasswordResetTokenStore implements PasswordResetTokenStore {

    private static final String COLLECTION = "password_reset_tokens";
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final MongoCollection<Document> collection;

    public MongoPasswordResetTokenStore(MongoDatabase db) {
        this.collection = Objects.requireNonNull(db, "db").getCollection(COLLECTION);
        this.collection.createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("password_reset_tokens_ttl"));
    }

    @Override
    public void store(PasswordResetToken token, Duration ttl) {
        Objects.requireNonNull(token, "token");
        collection.replaceOne(
                Filters.eq("_id", token.tokenId()),
                new Document("_id", token.tokenId())
                        .append("username", token.username())
                        .append("expiresAt", Date.from(token.expiresAt())),
                UPSERT);
    }

    @Override
    public Optional<PasswordResetToken> take(String tokenId) {
        if (tokenId == null) return Optional.empty();
        Document doc = collection.findOneAndDelete(
                Filters.and(Filters.eq("_id", tokenId), Filters.gt("expiresAt", Date.from(Instant.now()))));
        if (doc == null) return Optional.empty();
        Date expiresAt = doc.getDate("expiresAt");
        if (expiresAt == null) return Optional.empty();
        return Optional.of(new PasswordResetToken(tokenId, doc.getString("username"), expiresAt.toInstant()));
    }
}
