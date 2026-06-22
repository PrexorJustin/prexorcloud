package me.prexorjustin.prexorcloud.controller.module.platform;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleStorageRequest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleStorage;
import me.prexorjustin.prexorcloud.api.module.platform.StorageQuotaExceededException;
import me.prexorjustin.prexorcloud.controller.module.runtime.MongoModuleDataStore;
import me.prexorjustin.prexorcloud.controller.runtime.RuntimeServices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

/**
 * Allocates stable, module-scoped persistent storage namespaces for platform
 * modules.
 *
 * <p>Module storage is Mongo-only: the Redis/Valkey backend was retired with the
 * single-store control-plane rewrite.
 */
public final class PlatformModuleStorageManager {

    public record StorageAllocation(
            String moduleId,
            boolean mongoRequested,
            boolean mongoAvailable,
            String mongoDatabaseName,
            String mongoCollectionPrefix,
            long mongoDocumentLimit) {

        public boolean mongoAssigned() {
            return mongoRequested && mongoAvailable;
        }
    }

    public record StorageDropResult(String moduleId, int mongoCollectionsDropped) {}

    private final MongoDatabase mongoDatabase;
    private final MongoClient mongoClient;
    private final ObjectMapper objectMapper;

    public PlatformModuleStorageManager(
            MongoDatabase mongoDatabase, MongoClient mongoClient, RuntimeServices runtime, ObjectMapper objectMapper) {
        this.mongoDatabase = mongoDatabase;
        this.mongoClient = mongoClient;
        // runtime is no longer consulted (module storage is Mongo-only); the parameter is kept
        // until the RuntimeServices wiring is reshaped so existing call sites stay unchanged.
        Objects.requireNonNull(runtime, "runtime");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public PlatformModuleStorage resolve(PlatformModuleManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");

        StorageAllocation allocation = describe(manifest.id(), manifest.storage());
        if (allocation.mongoRequested() && !allocation.mongoAvailable()) {
            throw new IllegalStateException(
                    "module '" + manifest.id() + "' requested Mongo storage but Mongo is not configured");
        }

        ModuleDataStore mongoDataStore = allocation.mongoAssigned()
                ? quotaEnforcedMongoDataStore(
                        MongoModuleDataStore.withCollectionPrefix(
                                allocation.mongoCollectionPrefix(), mongoDatabase, mongoClient, objectMapper),
                        allocation)
                : null;

        return new PlatformModuleStorage(
                manifest.id(),
                manifest.storage(),
                allocation.mongoDatabaseName(),
                allocation.mongoCollectionPrefix(),
                mongoDataStore);
    }

    public StorageAllocation describe(String moduleId, ModuleStorageRequest request) {
        Objects.requireNonNull(moduleId, "moduleId");
        ModuleStorageRequest effectiveRequest = request == null ? ModuleStorageRequest.NONE : request;
        String sanitized = sanitizeModuleId(moduleId);
        return new StorageAllocation(
                moduleId,
                effectiveRequest.mongo(),
                mongoDatabase != null && mongoClient != null,
                mongoDatabase != null ? mongoDatabase.getName() : null,
                effectiveRequest.mongo() ? "platform_" + sanitized + "_" : null,
                effectiveRequest.limits().mongoDocuments());
    }

    public StorageDropResult drop(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        String sanitized = sanitizeModuleId(moduleId);
        String mongoPrefix = "platform_" + sanitized + "_";

        int droppedCollections = 0;
        if (mongoDatabase != null) {
            for (String collectionName : mongoDatabase.listCollectionNames()) {
                if (collectionName.startsWith(mongoPrefix)) {
                    mongoDatabase.getCollection(collectionName).drop();
                    droppedCollections++;
                }
            }
        }

        return new StorageDropResult(moduleId, droppedCollections);
    }

    private static String sanitizeModuleId(String moduleId) {
        return moduleId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private ModuleDataStore quotaEnforcedMongoDataStore(MongoModuleDataStore delegate, StorageAllocation allocation) {
        if (allocation.mongoDocumentLimit() <= 0) {
            return delegate;
        }
        return new QuotaEnforcedModuleDataStore(
                allocation.moduleId(), delegate, allocation.mongoCollectionPrefix(), allocation.mongoDocumentLimit());
    }

    private final class QuotaEnforcedModuleDataStore implements ModuleDataStore {

        private final String moduleId;
        private final ModuleDataStore delegate;
        private final String collectionPrefix;
        private final long maxDocuments;

        private QuotaEnforcedModuleDataStore(
                String moduleId, ModuleDataStore delegate, String collectionPrefix, long maxDocuments) {
            this.moduleId = moduleId;
            this.delegate = delegate;
            this.collectionPrefix = collectionPrefix;
            this.maxDocuments = maxDocuments;
        }

        @Override
        public String collectionPrefix() {
            return delegate.collectionPrefix();
        }

        @Override
        public void ensureCollection(String name) {
            delegate.ensureCollection(name);
        }

        @Override
        public void createIndex(String collection, me.prexorjustin.prexorcloud.api.module.data.IndexSpec index) {
            delegate.createIndex(collection, index);
        }

        @Override
        public <T> String insertOne(String collection, T document) {
            ensureMongoDocumentCapacity(1);
            return delegate.insertOne(collection, document);
        }

        @Override
        public <T> int insertMany(String collection, List<T> documents) {
            ensureMongoDocumentCapacity(documents.size());
            return delegate.insertMany(collection, documents);
        }

        @Override
        public <T> Optional<T> findOne(
                String collection, me.prexorjustin.prexorcloud.api.module.data.Query filter, Class<T> type) {
            return delegate.findOne(collection, filter, type);
        }

        @Override
        public <T> List<T> find(
                String collection,
                me.prexorjustin.prexorcloud.api.module.data.Query filter,
                me.prexorjustin.prexorcloud.api.module.data.Sort sort,
                int limit,
                Class<T> type) {
            return delegate.find(collection, filter, sort, limit, type);
        }

        @Override
        public <T> List<T> find(
                String collection,
                me.prexorjustin.prexorcloud.api.module.data.Query filter,
                me.prexorjustin.prexorcloud.api.module.data.Sort sort,
                int limit,
                int skip,
                Class<T> type) {
            return delegate.find(collection, filter, sort, limit, skip, type);
        }

        @Override
        public long count(String collection, me.prexorjustin.prexorcloud.api.module.data.Query filter) {
            return delegate.count(collection, filter);
        }

        @Override
        public int updateOne(
                String collection,
                me.prexorjustin.prexorcloud.api.module.data.Query filter,
                me.prexorjustin.prexorcloud.api.module.data.Update update) {
            return delegate.updateOne(collection, filter, update);
        }

        @Override
        public int updateMany(
                String collection,
                me.prexorjustin.prexorcloud.api.module.data.Query filter,
                me.prexorjustin.prexorcloud.api.module.data.Update update) {
            return delegate.updateMany(collection, filter, update);
        }

        @Override
        public boolean upsertOne(
                String collection,
                me.prexorjustin.prexorcloud.api.module.data.Query filter,
                me.prexorjustin.prexorcloud.api.module.data.Update update) {
            if (delegate.count(collection, filter) == 0) {
                ensureMongoDocumentCapacity(1);
            }
            return delegate.upsertOne(collection, filter, update);
        }

        @Override
        public boolean deleteOne(String collection, me.prexorjustin.prexorcloud.api.module.data.Query filter) {
            return delegate.deleteOne(collection, filter);
        }

        @Override
        public int deleteMany(String collection, me.prexorjustin.prexorcloud.api.module.data.Query filter) {
            return delegate.deleteMany(collection, filter);
        }

        @Override
        public void withTransaction(TransactionWork work) {
            delegate.withTransaction(txStore ->
                    work.execute(new QuotaEnforcedModuleDataStore(moduleId, txStore, collectionPrefix, maxDocuments)));
        }

        private void ensureMongoDocumentCapacity(int requestedDocuments) {
            if (requestedDocuments <= 0) {
                return;
            }
            long currentDocuments = currentMongoDocumentCount(collectionPrefix);
            if (currentDocuments + requestedDocuments > maxDocuments) {
                throw new StorageQuotaExceededException("module '"
                        + moduleId
                        + "' would exceed Mongo document soft limit "
                        + maxDocuments
                        + " (current="
                        + currentDocuments
                        + ", requested="
                        + requestedDocuments
                        + ")");
            }
        }
    }

    private long currentMongoDocumentCount(String collectionPrefix) {
        long documents = 0;
        for (String collectionName : mongoDatabase.listCollectionNames()) {
            if (collectionName.startsWith(collectionPrefix)) {
                documents += mongoDatabase.getCollection(collectionName).countDocuments();
            }
        }
        return documents;
    }
}
