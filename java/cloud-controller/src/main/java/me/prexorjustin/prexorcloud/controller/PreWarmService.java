package me.prexorjustin.prexorcloud.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfigLoader;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.PreWarmCache;
import me.prexorjustin.prexorcloud.protocol.PreWarmEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsible for building and sending pre-warm cache instructions to nodes.
 *
 * <p>
 * This service extracts required platform/version combinations from all groups,
 * resolves them against the catalog, and sends the required download metadata
 * to a node.
 * </p>
 *
 * <p>
 * Optimizations:
 * <ul>
 * <li>Caches catalog lookups per platform</li>
 * <li>Prevents duplicate platform/version combinations</li>
 * </ul>
 */
final class PreWarmService {

    private static final Logger logger = LoggerFactory.getLogger(PreWarmService.class);

    private final GroupManager groupManager;
    private final CatalogStore catalogStore;

    public PreWarmService(GroupManager groupManager, CatalogStore catalogStore) {
        this.groupManager = groupManager;
        this.catalogStore = catalogStore;
    }

    /**
     * Sends pre-warm cache entries to a specific node.
     *
     * @param session
     *            target node session
     */
    public void sendTo(NodeSession session) {
        try {
            List<PreWarmEntry> entries = buildEntries();
            if (entries.isEmpty()) {
                return;
            }

            session.send(ControllerMessage.newBuilder()
                    .setPreWarmCache(PreWarmCache.newBuilder().addAllEntries(entries))
                    .build());
        } catch (IOException e) {
            logger.warn("Failed to send pre-warm cache: {}", e.getMessage(), e);
        }
    }

    /**
     * Builds all required pre-warm entries based on current groups.
     *
     * @return list of pre-warm entries
     * @throws IOException
     *             if catalog data cannot be loaded
     */
    public List<PreWarmEntry> buildEntries() throws IOException {
        var seen = new HashSet<PlatformVersion>();
        var entries = new ArrayList<PreWarmEntry>();
        var cache = new HashMap<String, List<CatalogConfigLoader.CatalogEntry>>();

        for (var group : groupManager.getAll()) {
            var resolved = groupManager.resolveGroup(group.name());

            String platform = resolved.platform();
            String version = resolved.platformVersion();

            if (platform == null || version == null || platform.isBlank() || version.isBlank()) {
                continue;
            }

            if (!seen.add(new PlatformVersion(platform, version))) {
                continue;
            }

            var tmp = cache.get(platform);
            if (tmp == null) {
                tmp = catalogStore.getByPlatform(platform);
                cache.put(platform, tmp);
            }

            final var catalogEntries = tmp;

            var match = catalogEntries.stream()
                    .filter(entry -> version.equals(entry.version()))
                    .findFirst()
                    .or(() -> catalogEntries.stream()
                            .filter(CatalogConfigLoader.CatalogEntry::recommended)
                            .findFirst());

            if (match.isEmpty()) {
                continue;
            }

            var catalogEntry = match.get();

            entries.add(PreWarmEntry.newBuilder()
                    .setPlatform(platform)
                    .setPlatformVersion(catalogEntry.version())
                    .setConfigFormat(me.prexorjustin.prexorcloud.controller.grpc.DaemonServiceImpl.parseConfigFormat(
                            catalogEntry.configFormat()))
                    .setJarFile(resolved.jarFile())
                    .setDownloadUrl(catalogEntry.downloadUrl())
                    .setSha256(catalogEntry.sha256())
                    .build());
        }

        return entries;
    }

    /**
     * Internal key for deduplication of platform/version combinations.
     */
    private record PlatformVersion(String platform, String version) {}
}
