package me.prexorjustin.prexorcloud.common.cache;

/**
 * Domain records for daemon-reported cache state. Kept here so daemon (which
 * produces them while listing on-disk caches) and controller (which receives
 * and aggregates them via gRPC) share a single definition.
 *
 * <p>These are NOT the protobuf wire types — see {@code TemplateCacheEntry},
 * {@code JarCacheEntry}, {@code BootstrapCacheEntry} in {@code cloud-protocol}.
 */
public final class CacheEntries {

    private CacheEntries() {}

    public record TemplateCacheInfo(String name, String hash, long sizeBytes, String lastUsed) {}

    public record JarCacheInfo(
            String platform, String version, String jarFile, long sizeBytes, String sha256, String cachedAt) {}

    public record BootstrapCacheInfo(String configFormat, String version, boolean hasCds, long sizeBytes) {}
}
