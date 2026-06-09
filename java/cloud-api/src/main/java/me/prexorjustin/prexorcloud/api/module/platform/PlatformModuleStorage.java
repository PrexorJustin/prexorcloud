package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.Objects;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;

/**
 * Controller-owned persistent storage handles scoped to one platform module.
 */
public record PlatformModuleStorage(
        String moduleId,
        ModuleStorageRequest request,
        String mongoDatabaseName,
        String mongoCollectionPrefix,
        String redisKeyPrefix,
        ModuleDataStore mongoDataStore,
        PlatformRedisStorage redisStorage) {

    public PlatformModuleStorage {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId must not be blank");
        }
        request = request == null ? ModuleStorageRequest.NONE : request;
    }

    public static PlatformModuleStorage none(String moduleId, ModuleStorageRequest request) {
        return new PlatformModuleStorage(moduleId, request, null, null, null, null, null);
    }

    public boolean hasStorage() {
        return mongoDataStore != null || redisStorage != null;
    }

    public Optional<ModuleDataStore> mongo() {
        return Optional.ofNullable(mongoDataStore);
    }

    public ModuleDataStore requireMongo() {
        return mongo().orElseThrow(() ->
                new IllegalStateException("mongo storage is not available for module '" + moduleId + "'"));
    }

    public Optional<PlatformRedisStorage> redis() {
        return Optional.ofNullable(redisStorage);
    }

    public PlatformRedisStorage requireRedis() {
        return redis().orElseThrow(() ->
                new IllegalStateException("redis storage is not available for module '" + moduleId + "'"));
    }

    public String requireMongoCollectionPrefix() {
        return Objects.requireNonNull(mongoCollectionPrefix, "mongoCollectionPrefix");
    }

    public String requireRedisKeyPrefix() {
        return Objects.requireNonNull(redisKeyPrefix, "redisKeyPrefix");
    }
}
