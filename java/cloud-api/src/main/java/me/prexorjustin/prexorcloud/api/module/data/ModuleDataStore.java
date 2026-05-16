package me.prexorjustin.prexorcloud.api.module.data;

import java.util.List;
import java.util.Optional;

/**
 * Document-oriented data store scoped to the owning module's collection
 * namespace. Documents are serialised via Jackson — modules work with their own
 * Java records and the implementation handles conversion.
 */
public interface ModuleDataStore {

    /** Collection name prefix for this module (e.g. {@code "mod_message_"}). */
    String collectionPrefix();

    /** Ensure the named collection exists, creating it if necessary. */
    void ensureCollection(String name);

    /** Create an index on the named collection. */
    void createIndex(String collection, IndexSpec index);

    // ── Insert ──────────────────────────────────────────────────────────────

    /**
     * Insert a single document (Jackson-serialised). Returns the generated id as
     * string.
     */
    <T> String insertOne(String collection, T document);

    /** Insert multiple documents. Returns the number inserted. */
    <T> int insertMany(String collection, List<T> documents);

    // ── Read ────────────────────────────────────────────────────────────────

    /**
     * Find the first document matching the filter, deserialised to {@code type}.
     */
    <T> Optional<T> findOne(String collection, Query filter, Class<T> type);

    /** Find documents matching the filter with sort and limit. */
    <T> List<T> find(String collection, Query filter, Sort sort, int limit, Class<T> type);

    /**
     * Find documents matching the filter with sort, limit, and skip (pagination).
     */
    <T> List<T> find(String collection, Query filter, Sort sort, int limit, int skip, Class<T> type);

    /** Count documents matching the filter. */
    long count(String collection, Query filter);

    // ── Update ──────────────────────────────────────────────────────────────

    /** Update the first document matching the filter. Returns modified count. */
    int updateOne(String collection, Query filter, Update update);

    /** Update all documents matching the filter. Returns modified count. */
    int updateMany(String collection, Query filter, Update update);

    /**
     * Upsert: update if exists, insert otherwise. Returns true if a new document
     * was created.
     */
    boolean upsertOne(String collection, Query filter, Update update);

    // ── Delete ──────────────────────────────────────────────────────────────

    /**
     * Delete the first document matching the filter. Returns true if a document was
     * deleted.
     */
    boolean deleteOne(String collection, Query filter);

    /** Delete all documents matching the filter. Returns the number deleted. */
    int deleteMany(String collection, Query filter);

    // ── Transaction ─────────────────────────────────────────────────────────

    /** Work to execute inside a transaction. */
    @FunctionalInterface
    interface TransactionWork {

        void execute(ModuleDataStore txStore) throws Exception;
    }

    /** Execute work inside a multi-document transaction. */
    void withTransaction(TransactionWork work);
}
