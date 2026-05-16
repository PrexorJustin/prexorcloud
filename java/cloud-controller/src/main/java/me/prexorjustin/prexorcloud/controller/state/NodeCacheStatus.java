package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.List;

import me.prexorjustin.prexorcloud.common.cache.CacheEntries.BootstrapCacheInfo;
import me.prexorjustin.prexorcloud.common.cache.CacheEntries.JarCacheInfo;
import me.prexorjustin.prexorcloud.common.cache.CacheEntries.TemplateCacheInfo;

/**
 * Snapshot of a daemon's cache state, received via gRPC CacheStatus message.
 *
 * <p>The element types live in {@code cloud-common} so the daemon (which
 * produces them) and the controller (which receives them) share definitions.
 */
public record NodeCacheStatus(
        List<TemplateCacheInfo> templates,
        List<JarCacheInfo> jars,
        List<BootstrapCacheInfo> bootstraps,
        long totalSizeBytes,
        Instant receivedAt) {}
