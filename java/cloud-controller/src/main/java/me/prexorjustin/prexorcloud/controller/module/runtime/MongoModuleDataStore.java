package me.prexorjustin.prexorcloud.controller.module.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.data.DataStoreException;
import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;
import me.prexorjustin.prexorcloud.api.module.data.Update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

/**
 * MongoDB-backed {@link ModuleDataStore}. Each module gets an isolated
 * collection namespace via {@link #collectionPrefix()}.
 */
public final class MongoModuleDataStore implements ModuleDataStore {

    private final String collectionPrefix;
    private final MongoDatabase database;
    private final MongoClient mongoClient;
    private final ObjectMapper objectMapper;
    private final ClientSession session; // null outside transactions

    public MongoModuleDataStore(
            String moduleName, MongoDatabase database, MongoClient mongoClient, ObjectMapper objectMapper) {
        this("mod_" + sanitizeName(moduleName) + "_", database, mongoClient, objectMapper, null);
    }

    public static MongoModuleDataStore withCollectionPrefix(
            String collectionPrefix, MongoDatabase database, MongoClient mongoClient, ObjectMapper objectMapper) {
        return new MongoModuleDataStore(collectionPrefix, database, mongoClient, objectMapper, null);
    }

    private MongoModuleDataStore(
            String collectionPrefix,
            MongoDatabase database,
            MongoClient mongoClient,
            ObjectMapper objectMapper,
            ClientSession session) {
        this.collectionPrefix = collectionPrefix;
        this.database = database;
        this.mongoClient = mongoClient;
        this.objectMapper = objectMapper;
        this.session = session;
    }

    // ── ModuleDataStore API ──────────────────────────────────────────────────

    @Override
    public String collectionPrefix() {
        return collectionPrefix;
    }

    @Override
    public void ensureCollection(String name) {
        var fullName = collectionPrefix + name;
        // createCollection is idempotent in practice; catch "already exists"
        try {
            database.createCollection(fullName);
        } catch (com.mongodb.MongoCommandException e) {
            if (e.getErrorCode() != 48) throw new DataStoreException("Failed to create collection: " + fullName, e);
        }
    }

    @Override
    public void createIndex(String collection, IndexSpec index) {
        var col = collection(collection);
        var keys = new Document();
        index.keys().forEach(keys::append);
        var opts = new IndexOptions();
        if (index.unique()) opts.unique(true);
        if (index.name() != null) opts.name(index.name());
        col.createIndex(keys, opts);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> String insertOne(String collection, T document) {
        var col = collection(collection);
        var doc = toDocument(document);
        if (session != null) col.insertOne(session, doc);
        else col.insertOne(doc);
        return objectIdToString(doc.get("_id"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> int insertMany(String collection, List<T> documents) {
        if (documents.isEmpty()) return 0;
        var col = collection(collection);
        var docs = documents.stream().map(this::toDocument).toList();
        if (session != null) col.insertMany(session, docs);
        else col.insertMany(docs);
        return docs.size();
    }

    @Override
    public <T> Optional<T> findOne(String collection, Query filter, Class<T> type) {
        var col = collection(collection);
        var bsonFilter = toBson(filter);
        Document doc = session != null
                ? col.find(session, bsonFilter).first()
                : col.find(bsonFilter).first();
        return Optional.ofNullable(doc).map(d -> toRecord(d, type));
    }

    @Override
    public <T> List<T> find(String collection, Query filter, Sort sort, int limit, Class<T> type) {
        return find(collection, filter, sort, limit, 0, type);
    }

    @Override
    public <T> List<T> find(String collection, Query filter, Sort sort, int limit, int skip, Class<T> type) {
        var col = collection(collection);
        var bsonFilter = toBson(filter);
        var bsonSort = toBson(sort);
        var cursor = session != null ? col.find(session, bsonFilter) : col.find(bsonFilter);
        if (!sort.isEmpty()) cursor = cursor.sort(bsonSort);
        if (skip > 0) cursor = cursor.skip(skip);
        if (limit > 0) cursor = cursor.limit(limit);
        var results = new ArrayList<T>();
        for (var doc : cursor) results.add(toRecord(doc, type));
        return results;
    }

    @Override
    public long count(String collection, Query filter) {
        var col = collection(collection);
        var bsonFilter = toBson(filter);
        return session != null ? col.countDocuments(session, bsonFilter) : col.countDocuments(bsonFilter);
    }

    @Override
    public int updateOne(String collection, Query filter, Update update) {
        var col = collection(collection);
        UpdateResult result = session != null
                ? col.updateOne(session, toBson(filter), toBson(update))
                : col.updateOne(toBson(filter), toBson(update));
        return (int) result.getModifiedCount();
    }

    @Override
    public int updateMany(String collection, Query filter, Update update) {
        var col = collection(collection);
        UpdateResult result = session != null
                ? col.updateMany(session, toBson(filter), toBson(update))
                : col.updateMany(toBson(filter), toBson(update));
        return (int) result.getModifiedCount();
    }

    @Override
    public boolean upsertOne(String collection, Query filter, Update update) {
        var col = collection(collection);
        var opts = new com.mongodb.client.model.UpdateOptions().upsert(true);
        UpdateResult result = session != null
                ? col.updateOne(session, toBson(filter), toBson(update), opts)
                : col.updateOne(toBson(filter), toBson(update), opts);
        return result.getUpsertedId() != null;
    }

    @Override
    public boolean deleteOne(String collection, Query filter) {
        var col = collection(collection);
        DeleteResult result = session != null ? col.deleteOne(session, toBson(filter)) : col.deleteOne(toBson(filter));
        return result.getDeletedCount() > 0;
    }

    @Override
    public int deleteMany(String collection, Query filter) {
        var col = collection(collection);
        DeleteResult result =
                session != null ? col.deleteMany(session, toBson(filter)) : col.deleteMany(toBson(filter));
        return (int) result.getDeletedCount();
    }

    @Override
    public void withTransaction(TransactionWork work) {
        try (var txSession = mongoClient.startSession()) {
            txSession.withTransaction(() -> {
                var txStore =
                        new MongoModuleDataStore(collectionPrefix, database, mongoClient, objectMapper, txSession);
                try {
                    work.execute(txStore);
                } catch (Exception e) {
                    throw new DataStoreException("Transaction failed", e);
                }
                return null;
            });
        }
    }

    // ── BSON conversion ──────────────────────────────────────────────────────

    private Bson toBson(Query query) {
        if (query.isEmpty()) return new Document();

        // Composite queries (or/and of sub-queries)
        if (!query.children().isEmpty()) {
            var childFilters = query.children().stream().map(this::toBson).toList();
            return query.combinator() == Query.LogicalOp.OR ? Filters.or(childFilters) : Filters.and(childFilters);
        }

        // Flat condition list
        var conditions = query.conditions();
        if (conditions.isEmpty()) return new Document();

        var filters = conditions.stream().map(this::conditionToBson).toList();
        return query.combinator() == Query.LogicalOp.OR ? Filters.or(filters) : Filters.and(filters);
    }

    private Bson conditionToBson(Query.Condition c) {
        String field = c.field();
        Object value = maybeObjectId(field, c.value());
        return switch (c.operator()) {
            case "$eq" -> Filters.eq(field, value);
            case "$ne" -> Filters.ne(field, value);
            case "$gt" -> Filters.gt(field, value);
            case "$gte" -> Filters.gte(field, value);
            case "$lt" -> Filters.lt(field, value);
            case "$lte" -> Filters.lte(field, value);
            case "$in" -> Filters.in(field, (Collection<?>) value);
            case "$exists" -> Filters.exists(field, (Boolean) value);
            case "$regex" -> Filters.regex(field, (String) value);
            default -> throw new DataStoreException("Unsupported operator: " + c.operator());
        };
    }

    private Bson toBson(Update update) {
        var updates = new ArrayList<Bson>();
        update.sets().forEach((k, v) -> updates.add(Updates.set(k, v)));
        update.setOnInserts().forEach((k, v) -> updates.add(Updates.setOnInsert(k, v)));
        update.increments().forEach((k, v) -> updates.add(Updates.inc(k, (Number) v)));
        update.unsets().forEach(k -> updates.add(Updates.unset(k)));
        if (updates.isEmpty()) throw new DataStoreException("Empty update — nothing to apply");
        return Updates.combine(updates);
    }

    private Bson toBson(Sort sort) {
        if (sort.isEmpty()) return new Document();
        var sorts = sort.fields().stream()
                .map(e -> e.getValue() == 1 ? Sorts.ascending(e.getKey()) : Sorts.descending(e.getKey()))
                .toList();
        return Sorts.orderBy(sorts);
    }

    // ── Document / Record conversion ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Document toDocument(Object record) {
        var map = objectMapper.convertValue(record, Map.class);
        var doc = new Document((Map<String, Object>) map);
        doc.remove("_id"); // let MongoDB generate ObjectId
        return doc;
    }

    private <T> T toRecord(Document doc, Class<T> type) {
        // Convert ObjectId _id to string "id" for Jackson mapping
        if (doc.containsKey("_id")) {
            doc.put("id", objectIdToString(doc.get("_id")));
            doc.remove("_id");
        }
        return objectMapper.convertValue(doc, type);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MongoCollection<Document> collection(String name) {
        return database.getCollection(collectionPrefix + name);
    }

    private static Object maybeObjectId(String field, Object value) {
        if ("_id".equals(field) && value instanceof String s && ObjectId.isValid(s)) {
            return new ObjectId(s);
        }
        return value;
    }

    private static String objectIdToString(Object id) {
        if (id instanceof ObjectId oid) return oid.toHexString();
        return id != null ? id.toString() : null;
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-z0-9_]", "_");
    }
}
