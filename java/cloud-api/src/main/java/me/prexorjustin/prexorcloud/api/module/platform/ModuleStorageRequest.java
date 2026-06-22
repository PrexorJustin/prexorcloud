package me.prexorjustin.prexorcloud.api.module.platform;

/**
 * Persistent-storage request declared by a platform module. The controller
 * allocates a scoped Mongo namespace per module when storage is requested.
 */
public record ModuleStorageRequest(boolean mongo, StorageLimits limits) {

    public static final ModuleStorageRequest NONE = new ModuleStorageRequest(false);

    public ModuleStorageRequest(boolean mongo) {
        this(mongo, StorageLimits.NONE);
    }

    public ModuleStorageRequest {
        limits = limits == null ? StorageLimits.NONE : limits;
        if (!mongo && limits.hasMongoDocumentLimit()) {
            throw new IllegalArgumentException("mongoDocuments limit requires mongo storage");
        }
    }

    public boolean isEmpty() {
        return !mongo && limits.isEmpty();
    }

    public record StorageLimits(long mongoDocuments) {

        public static final StorageLimits NONE = new StorageLimits(0);

        public StorageLimits {
            if (mongoDocuments < 0) {
                throw new IllegalArgumentException("mongoDocuments limit must not be negative");
            }
        }

        public boolean hasMongoDocumentLimit() {
            return mongoDocuments > 0;
        }

        public boolean isEmpty() {
            return mongoDocuments == 0;
        }
    }
}
