package me.prexorjustin.prexorcloud.controller.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed user storage.
 */
public final class MongoUserStore implements UserStore {

    private static final Logger logger = LoggerFactory.getLogger(MongoUserStore.class);
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final MongoCollection<Document> users;

    public MongoUserStore(MongoDatabase db) {
        this.users = db.getCollection("users");
        this.users.createIndex(
                Indexes.ascending("email"),
                new IndexOptions().name("email_unique").sparse(true).unique(true));
    }

    @Override
    public List<User> loadAll() {
        var list = new ArrayList<User>();
        for (var doc : users.find().sort(Sorts.ascending("_id"))) {
            list.add(toUser(doc));
        }
        return list;
    }

    @Override
    public Optional<User> getByUsername(String username) {
        var doc = users.find(Filters.eq("_id", username)).first();
        return Optional.ofNullable(doc).map(MongoUserStore::toUser);
    }

    @Override
    public User create(String username, String passwordHash, String role) {
        var user = new User(
                username, passwordHash, role, null, null, null, Instant.now().toString(), null);
        users.insertOne(toDocument(user));
        logger.debug("Created user: {}", username);
        return user;
    }

    @Override
    public void update(String username, String newUsername, String role, String passwordHash) {
        var doc = users.find(Filters.eq("_id", username)).first();
        if (doc == null) return;

        var existing = toUser(doc);
        var updated = new User(
                newUsername != null ? newUsername : existing.username(),
                passwordHash != null ? passwordHash : existing.passwordHash(),
                role != null ? role : existing.role(),
                existing.avatarPath(),
                existing.minecraftUuid(),
                existing.minecraftName(),
                existing.createdAt(),
                existing.email());

        if (newUsername != null && !newUsername.equals(username)) {
            users.deleteOne(Filters.eq("_id", username));
            users.insertOne(toDocument(updated));
        } else {
            users.replaceOne(Filters.eq("_id", username), toDocument(updated));
        }
        logger.debug("Updated user: {}", username);
    }

    @Override
    public void updateAvatar(String username, String avatarPath) {
        users.updateOne(Filters.eq("_id", username), new Document("$set", new Document("avatarPath", avatarPath)));
    }

    @Override
    public void updateMinecraftLink(String username, String minecraftUuid, String minecraftName) {
        users.updateOne(
                Filters.eq("_id", username),
                new Document(
                        "$set", new Document("minecraftUuid", minecraftUuid).append("minecraftName", minecraftName)));
    }

    @Override
    public void delete(String username) {
        users.deleteOne(Filters.eq("_id", username));
        logger.debug("Deleted user: {}", username);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        var doc = users.find(Filters.eq("email", email.toLowerCase())).first();
        return Optional.ofNullable(doc).map(MongoUserStore::toUser);
    }

    @Override
    public void updateEmail(String username, String email) {
        String normalized = (email == null || email.isBlank()) ? null : email.toLowerCase();
        Bson update = normalized == null
                ? new Document("$unset", new Document("email", ""))
                : new Document("$set", new Document("email", normalized));
        users.updateOne(Filters.eq("_id", username), update);
    }

    /**
     * Seed default roles on first boot if no users exist.
     */
    public void ensureAdminExists(String username, String passwordHash) {
        if (users.countDocuments() == 0) {
            create(username, passwordHash, "ADMIN");
            logger.info("Seeded admin user: {}", username);
        }
    }

    private static Document toDocument(User user) {
        var doc = new Document("_id", user.username())
                .append("passwordHash", user.passwordHash())
                .append("role", user.role())
                .append("avatarPath", user.avatarPath())
                .append("minecraftUuid", user.minecraftUuid())
                .append("minecraftName", user.minecraftName())
                .append("createdAt", user.createdAt());
        if (user.email() != null) doc.append("email", user.email());
        return doc;
    }

    private static User toUser(Document doc) {
        return new User(
                doc.getString("_id"),
                doc.getString("passwordHash"),
                doc.getString("role"),
                doc.getString("avatarPath"),
                doc.getString("minecraftUuid"),
                doc.getString("minecraftName"),
                doc.getString("createdAt"),
                doc.getString("email"));
    }
}
