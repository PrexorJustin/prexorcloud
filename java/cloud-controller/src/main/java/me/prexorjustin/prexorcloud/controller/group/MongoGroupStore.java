package me.prexorjustin.prexorcloud.controller.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed group configuration storage.
 */
public final class MongoGroupStore implements GroupStore {

    private static final Logger logger = LoggerFactory.getLogger(MongoGroupStore.class);
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final MongoCollection<Document> groups;

    public MongoGroupStore(MongoDatabase db) {
        this.groups = db.getCollection("groups");
    }

    @Override
    public List<GroupConfig> loadAll() {
        var configs = new ArrayList<GroupConfig>();
        for (var doc : groups.find().sort(Sorts.ascending("_id"))) {
            try {
                configs.add(toGroupConfig(doc));
            } catch (Exception e) {
                logger.warn("Failed to deserialize group '{}': {}", doc.getString("_id"), e.getMessage());
            }
        }
        logger.info("Loaded {} group(s) from MongoDB", configs.size());
        return configs;
    }

    @Override
    public void save(GroupConfig config) {
        var doc = toDocument(config);
        groups.replaceOne(Filters.eq("_id", config.name()), doc, UPSERT);
        logger.debug("Saved group config: {}", config.name());
    }

    @Override
    public void delete(String name) {
        groups.deleteOne(Filters.eq("_id", name));
        logger.debug("Deleted group config: {}", name);
    }

    @SuppressWarnings("unchecked")
    private static Document toDocument(GroupConfig config) {
        // Serialize via Jackson to Map, then to Document — preserves all 52 fields
        var map = MAPPER.convertValue(config, Map.class);
        var doc = new Document(map);
        doc.put("_id", config.name());
        doc.remove("name"); // _id is the name
        return doc;
    }

    private static GroupConfig toGroupConfig(Document doc) {
        // Put name back from _id for deserialization
        doc.put("name", doc.getString("_id"));
        return MAPPER.convertValue(doc, GroupConfig.class);
    }
}
