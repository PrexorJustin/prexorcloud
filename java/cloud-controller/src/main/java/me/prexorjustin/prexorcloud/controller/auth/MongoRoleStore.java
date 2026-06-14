package me.prexorjustin.prexorcloud.controller.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed role storage.
 */
public final class MongoRoleStore implements RoleStore {

    private static final Logger logger = LoggerFactory.getLogger(MongoRoleStore.class);
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final MongoCollection<Document> roles;

    public MongoRoleStore(MongoDatabase db) {
        this.roles = db.getCollection("roles");
    }

    @Override
    public List<RoleConfig> loadAll() {
        var list = new ArrayList<RoleConfig>();
        for (var doc : roles.find().sort(Sorts.ascending("_id"))) {
            list.add(toRoleConfig(doc));
        }
        return list;
    }

    @Override
    public Optional<RoleConfig> get(String name) {
        var doc = roles.find(Filters.eq("_id", name)).first();
        return Optional.ofNullable(doc).map(MongoRoleStore::toRoleConfig);
    }

    @Override
    public void save(RoleConfig role) {
        roles.replaceOne(Filters.eq("_id", role.name()), toDocument(role), UPSERT);
        logger.debug("Saved role: {}", role.name());
    }

    @Override
    public void delete(String name) {
        roles.deleteOne(Filters.eq("_id", name));
        logger.debug("Deleted role: {}", name);
    }

    /**
     * Reconcile the built-in roles from the code-authoritative definitions in {@link Role} on every
     * startup. This is an upsert, not a seed-if-empty: a role doc written by an older build silently
     * shadows the reflective code defaults, so without re-reconciling, existing clusters keep denying
     * ADMIN any permission added after the doc was first seeded (this is exactly how ADMIN ended up
     * unable to view {@code system.logs.view}). Custom roles — any name not in
     * {@link Role#builtInDefaults()} — are left untouched.
     */
    public void ensureDefaults() {
        var defaults = Role.builtInDefaults();
        defaults.forEach((name, perms) -> save(new RoleConfig(name, List.copyOf(perms), true)));
        logger.info("Reconciled {} built-in roles from code defaults", defaults.size());
    }

    private static Document toDocument(RoleConfig role) {
        return new Document("_id", role.name())
                .append("permissions", role.permissions())
                .append("builtIn", role.builtIn());
    }

    private static RoleConfig toRoleConfig(Document doc) {
        @SuppressWarnings("unchecked")
        List<String> permissions = doc.getList("permissions", String.class, List.of());
        return new RoleConfig(doc.getString("_id"), permissions, doc.getBoolean("builtIn", false));
    }
}
