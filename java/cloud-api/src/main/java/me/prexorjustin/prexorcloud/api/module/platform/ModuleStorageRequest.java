package me.prexorjustin.prexorcloud.api.module.platform;

/**
 * Persistent-storage request declared by a platform module. The controller
 * allocates a scoped Mongo namespace and/or Redis prefix per module when
 * storage is requested.
 */
public record ModuleStorageRequest(boolean mongo, boolean redis, StorageLimits limits) {

    public static final ModuleStorageRequest NONE = new ModuleStorageRequest(false, false);

    public ModuleStorageRequest(boolean mongo, boolean redis) {
        this(mongo, redis, StorageLimits.NONE);
    }

    public ModuleStorageRequest {
        limits = limits == null ? StorageLimits.NONE : limits;
        if (!mongo && limits.hasMongoDocumentLimit()) {
            throw new IllegalArgumentException("mongoDocuments limit requires mongo storage");
        }
        if (!redis && limits.hasRedisKeyLimit()) {
            throw new IllegalArgumentException("redisKeys limit requires redis storage");
        }
    }

    public boolean isEmpty() {
        return !mongo && !redis && limits.isEmpty();
    }

    public record StorageLimits(long mongoDocuments, long redisKeys) {

        public static final StorageLimits NONE = new StorageLimits(0, 0);

        public StorageLimits {
            if (mongoDocuments < 0) {
                throw new IllegalArgumentException("mongoDocuments limit must not be negative");
            }
            if (redisKeys < 0) {
                throw new IllegalArgumentException("redisKeys limit must not be negative");
            }
        }

        public boolean hasMongoDocumentLimit() {
            return mongoDocuments > 0;
        }

        public boolean hasRedisKeyLimit() {
            return redisKeys > 0;
        }

        public boolean isEmpty() {
            return mongoDocuments == 0 && redisKeys == 0;
        }
    }
}
