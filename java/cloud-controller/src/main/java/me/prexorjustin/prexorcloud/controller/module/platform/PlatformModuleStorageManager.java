package me.prexorjustin.prexorcloud.controller.module.platform;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleStorageRequest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleStorage;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformRedisStorage;
import me.prexorjustin.prexorcloud.api.module.platform.StorageQuotaExceededException;
import me.prexorjustin.prexorcloud.controller.module.runtime.MongoModuleDataStore;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.controller.runtime.RuntimeServices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Allocates stable, module-scoped persistent storage namespaces for platform
 * modules.
 */
public final class PlatformModuleStorageManager {

    public record StorageAllocation(
            String moduleId,
            boolean mongoRequested,
            boolean mongoAvailable,
            String mongoDatabaseName,
            String mongoCollectionPrefix,
            long mongoDocumentLimit,
            boolean redisRequested,
            boolean redisAvailable,
            String redisKeyPrefix,
            long redisKeyLimit) {

        public boolean mongoAssigned() {
            return mongoRequested && mongoAvailable;
        }

        public boolean redisAssigned() {
            return redisRequested && redisAvailable;
        }
    }

    public record StorageDropResult(String moduleId, int mongoCollectionsDropped, int redisKeysDropped) {}

    private final MongoDatabase mongoDatabase;
    private final MongoClient mongoClient;
    private final RuntimeServices runtime;
    private final ObjectMapper objectMapper;

    public PlatformModuleStorageManager(
            MongoDatabase mongoDatabase, MongoClient mongoClient, RuntimeServices runtime, ObjectMapper objectMapper) {
        this.mongoDatabase = mongoDatabase;
        this.mongoClient = mongoClient;
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    private RedisCommands<String, String> redisCommands() {
        return runtime.coordinationEnabled() ? runtime.redisCommands() : null;
    }

    public PlatformModuleStorage resolve(PlatformModuleManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");

        StorageAllocation allocation = describe(manifest.id(), manifest.storage());
        if (allocation.mongoRequested() && !allocation.mongoAvailable()) {
            throw new IllegalStateException(
                    "module '" + manifest.id() + "' requested Mongo storage but Mongo is not configured");
        }
        if (allocation.redisRequested() && !allocation.redisAvailable()) {
            throw new IllegalStateException(
                    "module '" + manifest.id() + "' requested Redis storage but Redis is not configured");
        }

        ModuleDataStore mongoDataStore = allocation.mongoAssigned()
                ? quotaEnforcedMongoDataStore(
                        MongoModuleDataStore.withCollectionPrefix(
                                allocation.mongoCollectionPrefix(), mongoDatabase, mongoClient, objectMapper),
                        allocation)
                : null;
        PlatformRedisStorage redisStorage = allocation.redisAssigned()
                ? new LettucePlatformRedisStorage(
                        allocation.redisKeyPrefix(), redisCommands(), allocation.redisKeyLimit())
                : null;

        return new PlatformModuleStorage(
                manifest.id(),
                manifest.storage(),
                allocation.mongoDatabaseName(),
                allocation.mongoCollectionPrefix(),
                allocation.redisKeyPrefix(),
                mongoDataStore,
                redisStorage);
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
                effectiveRequest.limits().mongoDocuments(),
                effectiveRequest.redis(),
                runtime.coordinationEnabled(),
                effectiveRequest.redis() ? RedisKeys.platformModulePrefix(sanitized) : null,
                effectiveRequest.limits().redisKeys());
    }

    public StorageDropResult drop(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        String sanitized = sanitizeModuleId(moduleId);
        String mongoPrefix = "platform_" + sanitized + "_";
        String redisPrefix = RedisKeys.platformModulePrefix(sanitized);

        int droppedCollections = 0;
        if (mongoDatabase != null) {
            for (String collectionName : mongoDatabase.listCollectionNames()) {
                if (collectionName.startsWith(mongoPrefix)) {
                    mongoDatabase.getCollection(collectionName).drop();
                    droppedCollections++;
                }
            }
        }

        int droppedKeys = 0;
        RedisCommands<String, String> redis = redisCommands();
        if (redis != null) {
            List<String> keys = scanKeys(redis, redisPrefix + "*");
            if (!keys.isEmpty()) {
                droppedKeys = Math.toIntExact(redis.del(keys.toArray(String[]::new)));
            }
        }

        return new StorageDropResult(moduleId, droppedCollections, droppedKeys);
    }

    private List<String> scanKeys(RedisCommands<String, String> redis, String pattern) {
        List<String> keys = new ArrayList<>();
        KeyScanCursor<String> cursor =
                redis.scan(ScanArgs.Builder.matches(pattern).limit(500));
        keys.addAll(cursor.getKeys());
        while (!cursor.isFinished()) {
            cursor = redis.scan(cursor, ScanArgs.Builder.matches(pattern).limit(500));
            keys.addAll(cursor.getKeys());
        }
        return List.copyOf(keys);
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

    private static final class LettucePlatformRedisStorage implements PlatformRedisStorage {

        private final String keyPrefix;
        private final RedisCommands<String, String> redisCommands;
        private final long maxKeys;

        private LettucePlatformRedisStorage(
                String keyPrefix, RedisCommands<String, String> redisCommands, long maxKeys) {
            this.keyPrefix = keyPrefix;
            this.redisCommands = redisCommands;
            this.maxKeys = maxKeys;
        }

        @Override
        public String keyPrefix() {
            return keyPrefix;
        }

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(redisCommands.get(qualify(key)));
        }

        @Override
        public void set(String key, String value) {
            ensureRedisKeyCapacity(key);
            redisCommands.set(qualify(key), value);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            Objects.requireNonNull(ttl, "ttl");
            ensureRedisKeyCapacity(key);
            redisCommands.setex(qualify(key), ttl.toSeconds(), value);
        }

        @Override
        public long increment(String key) {
            ensureRedisKeyCapacity(key);
            return redisCommands.incr(qualify(key));
        }

        @Override
        public long decrement(String key) {
            ensureRedisKeyCapacity(key);
            return redisCommands.decr(qualify(key));
        }

        @Override
        public boolean delete(String key) {
            return redisCommands.del(qualify(key)) > 0;
        }

        private void ensureRedisKeyCapacity(String key) {
            if (maxKeys <= 0) {
                return;
            }
            String qualifiedKey = qualify(key);
            if (redisCommands.exists(qualifiedKey) > 0) {
                return;
            }
            long currentKeys = currentRedisKeyCount();
            if (currentKeys + 1 > maxKeys) {
                throw new StorageQuotaExceededException("redis storage would exceed key soft limit "
                        + maxKeys
                        + " for prefix '"
                        + keyPrefix
                        + "' (current="
                        + currentKeys
                        + ")");
            }
        }

        private long currentRedisKeyCount() {
            long keys = 0;
            KeyScanCursor<String> cursor =
                    redisCommands.scan(ScanArgs.Builder.matches(keyPrefix + "*").limit(500));
            keys += cursor.getKeys().size();
            while (!cursor.isFinished()) {
                cursor = redisCommands.scan(
                        cursor, ScanArgs.Builder.matches(keyPrefix + "*").limit(500));
                keys += cursor.getKeys().size();
            }
            return keys;
        }
    }
}
