package me.prexorjustin.prexorcloud.api.module.platform;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Redis-backed key/value storage scoped to one platform module.
 */
public interface PlatformRedisStorage {

    String keyPrefix();

    default String qualify(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return keyPrefix() + key;
    }

    Optional<String> get(String key);

    void set(String key, String value);

    void set(String key, String value, Duration ttl);

    long increment(String key);

    long decrement(String key);

    boolean delete(String key);
}
