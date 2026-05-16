package me.prexorjustin.prexorcloud.controller.auth;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;

import com.fasterxml.jackson.core.type.TypeReference;
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
     * Seed default roles from classpath if no roles exist in the database.
     */
    public void ensureDefaults() throws IOException {
        if (roles.countDocuments() > 0) return;

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("defaults/roles.yml")) {
            if (in == null) throw new IOException("Default roles.yml not found on classpath");
            var defaults = YamlConfigLoader.mapper().readValue(in, new TypeReference<List<RoleConfig>>() {});
            for (var role : defaults) {
                save(role);
            }
            logger.info("Seeded {} default roles", defaults.size());
        }
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
