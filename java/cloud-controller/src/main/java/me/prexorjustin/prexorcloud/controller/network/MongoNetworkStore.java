package me.prexorjustin.prexorcloud.controller.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;

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

/** MongoDB-backed persistence for {@link NetworkComposition}. */
public final class MongoNetworkStore implements NetworkStore {

    private static final Logger logger = LoggerFactory.getLogger(MongoNetworkStore.class);
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final MongoCollection<Document> networks;

    public MongoNetworkStore(MongoDatabase db) {
        this.networks = db.getCollection("networks");
    }

    @Override
    public List<NetworkComposition> loadAll() {
        var configs = new ArrayList<NetworkComposition>();
        for (var doc : networks.find().sort(Sorts.ascending("_id"))) {
            try {
                configs.add(toNetwork(doc));
            } catch (Exception e) {
                logger.warn("Failed to deserialize network '{}': {}", doc.getString("_id"), e.getMessage());
            }
        }
        logger.info("Loaded {} network composition(s) from MongoDB", configs.size());
        return configs;
    }

    @Override
    public void save(NetworkComposition network) {
        var doc = toDocument(network);
        networks.replaceOne(Filters.eq("_id", network.name()), doc, UPSERT);
        logger.debug("Saved network composition: {}", network.name());
    }

    @Override
    public void delete(String name) {
        networks.deleteOne(Filters.eq("_id", name));
        logger.debug("Deleted network composition: {}", name);
    }

    @SuppressWarnings("unchecked")
    private static Document toDocument(NetworkComposition network) {
        var map = MAPPER.convertValue(network, Map.class);
        var doc = new Document(map);
        doc.put("_id", network.name());
        doc.remove("name");
        return doc;
    }

    private static NetworkComposition toNetwork(Document doc) {
        doc.put("name", doc.getString("_id"));
        return MAPPER.convertValue(doc, NetworkComposition.class);
    }
}
