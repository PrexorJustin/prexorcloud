package me.prexorjustin.prexorcloud.controller.grpc;

import java.time.Instant;

import me.prexorjustin.prexorcloud.common.cache.CacheEntries.BootstrapCacheInfo;
import me.prexorjustin.prexorcloud.common.cache.CacheEntries.JarCacheInfo;
import me.prexorjustin.prexorcloud.common.cache.CacheEntries.TemplateCacheInfo;
import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.NodeCacheStatus;
import me.prexorjustin.prexorcloud.protocol.CacheStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates daemon-reported cache contents into {@link NodeCacheStatus} and
 * publishes them on {@link ClusterState}. Extracted from
 * {@code DaemonServiceImpl}'s connect-stream handler.
 */
final class DaemonCacheStatusReceiver {

    private static final Logger logger = LoggerFactory.getLogger(DaemonCacheStatusReceiver.class);

    private final ClusterState clusterState;

    DaemonCacheStatusReceiver(ClusterState clusterState) {
        this.clusterState = clusterState;
    }

    void handleCacheStatus(String nodeId, CacheStatus status) {
        if (nodeId == null) return;
        try {
            for (var t : status.getTemplatesList()) {
                InputValidator.requireSafeName(t.getName(), "templateName");
                InputValidator.requireNonNegativeLong(t.getSizeBytes(), "templateSizeBytes");
                InputValidator.requireMaxLength(t.getHash(), 128, "templateHash");
            }
            for (var j : status.getJarsList()) {
                InputValidator.requireNonNegativeLong(j.getSizeBytes(), "jarSizeBytes");
                InputValidator.requireMaxLength(j.getPlatform(), 64, "jarPlatform");
                InputValidator.requireMaxLength(j.getVersion(), 64, "jarVersion");
                InputValidator.requireMaxLength(j.getJarFile(), 128, "jarFile");
                InputValidator.requireMaxLength(j.getSha256(), 128, "jarSha256");
            }
            for (var b : status.getBootstrapsList()) {
                InputValidator.requireMaxLength(b.getVersion(), 64, "bootstrapVersion");
                InputValidator.requireNonNegativeLong(b.getSizeBytes(), "bootstrapSizeBytes");
            }
            InputValidator.requireNonNegativeLong(status.getTotalSizeBytes(), "totalSizeBytes");
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid handleCacheStatus from node {}: {}", nodeId, e.getMessage());
            return;
        }
        var templates = status.getTemplatesList().stream()
                .map(t -> new TemplateCacheInfo(
                        t.getName(),
                        t.getHash(),
                        t.getSizeBytes(),
                        Instant.ofEpochMilli(t.getLastUsedMs()).toString()))
                .toList();
        var jars = status.getJarsList().stream()
                .map(j -> new JarCacheInfo(
                        j.getPlatform(),
                        j.getVersion(),
                        j.getJarFile(),
                        j.getSizeBytes(),
                        j.getSha256(),
                        Instant.ofEpochMilli(j.getCachedAtMs()).toString()))
                .toList();
        var bootstraps = status.getBootstrapsList().stream()
                .map(b -> new BootstrapCacheInfo(
                        b.getConfigFormat().name(), b.getVersion(), b.getHasCds(), b.getSizeBytes()))
                .toList();
        var cacheStatus = new NodeCacheStatus(templates, jars, bootstraps, status.getTotalSizeBytes(), Instant.now());
        clusterState.updateCacheStatus(nodeId, cacheStatus);
        logger.debug(
                "Cache status from {}: {} templates, {} jars, {} bootstraps ({} bytes)",
                nodeId,
                templates.size(),
                jars.size(),
                bootstraps.size(),
                status.getTotalSizeBytes());
    }
}
