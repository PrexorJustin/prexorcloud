package me.prexorjustin.prexorcloud.controller.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfig.PlatformCatalog;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfig.VersionEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads, saves, and manages the software catalog from
 * {@code config/catalog.yml}. Creates an empty default file if missing.
 */
public final class CatalogConfigLoader implements CatalogStore {

    private static final Logger logger = LoggerFactory.getLogger(CatalogConfigLoader.class);

    private final Path catalogFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public CatalogConfigLoader(Path configDir) throws IOException {
        this.catalogFile = configDir.resolve("catalog.yml");
        ensureFile();
    }

    /**
     * Flat list of all catalog entries across all platforms.
     */
    public record CatalogEntry(
            String platform,
            String category,
            String configFormat,
            String version,
            String downloadUrl,
            String sha256,
            boolean recommended) {

        public boolean isProxy() {
            return "PROXY".equalsIgnoreCase(category);
        }
    }

    /**
     * Get all catalog entries as a flat list.
     */
    public List<CatalogEntry> getAll() throws IOException {
        lock.readLock().lock();
        try {
            var config = load();
            var entries = new ArrayList<CatalogEntry>();
            for (var p : config.platforms()) {
                for (var v : p.versions()) {
                    entries.add(new CatalogEntry(
                            p.platform(),
                            p.category(),
                            p.configFormat(),
                            v.version(),
                            v.downloadUrl(),
                            v.sha256(),
                            v.recommended()));
                }
            }
            return entries;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get catalog entries for a specific platform.
     */
    public List<CatalogEntry> getByPlatform(String platform) throws IOException {
        return getAll().stream()
                .filter(e -> e.platform().equalsIgnoreCase(platform))
                .toList();
    }

    /**
     * Get the platform catalog for a specific platform name.
     */
    public Optional<PlatformCatalog> getPlatform(String platform) throws IOException {
        lock.readLock().lock();
        try {
            return load().platforms().stream()
                    .filter(p -> p.platform().equalsIgnoreCase(platform))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Add a version entry. Returns {@code true} if this is the first entry for the
     * platform (i.e. a new platform was created).
     */
    public boolean addEntry(
            String platform, String category, String configFormat, String version, String downloadUrl, String sha256)
            throws IOException {
        lock.writeLock().lock();
        try {
            var config = load();
            var platforms = new ArrayList<>(config.platforms());

            Optional<PlatformCatalog> existing = platforms.stream()
                    .filter(p -> p.platform().equalsIgnoreCase(platform))
                    .findFirst();

            boolean newPlatform = existing.isEmpty();

            if (existing.isPresent()) {
                var pc = existing.get();
                platforms.remove(pc);
                var versions = new ArrayList<>(pc.versions());
                versions.removeIf(v -> v.version().equals(version));
                boolean recommended = versions.isEmpty();
                versions.add(new VersionEntry(version, downloadUrl, sha256, recommended));
                platforms.add(new PlatformCatalog(pc.platform(), pc.category(), pc.configFormat(), versions));
            } else {
                var entry = new VersionEntry(version, downloadUrl, sha256, true);
                platforms.add(new PlatformCatalog(platform, category, configFormat, List.of(entry)));
            }

            save(new CatalogConfig(platforms));
            logger.debug("Added catalog entry: {}/{}", platform, version);
            return newPlatform;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Update an existing version entry.
     */
    public void updateEntry(String platform, String oldVersion, String newVersion, String downloadUrl, String sha256)
            throws IOException {
        lock.writeLock().lock();
        try {
            var config = load();
            var platforms = new ArrayList<>(config.platforms());

            for (int i = 0; i < platforms.size(); i++) {
                var pc = platforms.get(i);
                if (!pc.platform().equalsIgnoreCase(platform)) continue;

                var versions = new ArrayList<>(pc.versions());
                boolean wasRecommended = versions.stream()
                        .filter(v -> v.version().equals(oldVersion))
                        .findFirst()
                        .map(VersionEntry::recommended)
                        .orElse(false);

                versions.removeIf(v -> v.version().equals(oldVersion));
                versions.add(new VersionEntry(newVersion, downloadUrl, sha256, wasRecommended));
                platforms.set(i, new PlatformCatalog(pc.platform(), pc.category(), pc.configFormat(), versions));
                break;
            }

            save(new CatalogConfig(platforms));
            logger.debug("Updated catalog entry: {}/{} -> {}", platform, oldVersion, newVersion);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Remove a version entry. Removes the platform entirely if it was the last
     * version.
     */
    public void removeEntry(String platform, String version) throws IOException {
        lock.writeLock().lock();
        try {
            var config = load();
            var platforms = new ArrayList<>(config.platforms());

            for (int i = 0; i < platforms.size(); i++) {
                var pc = platforms.get(i);
                if (!pc.platform().equalsIgnoreCase(platform)) continue;

                var versions = new ArrayList<>(pc.versions());
                versions.removeIf(v -> v.version().equals(version));
                if (versions.isEmpty()) {
                    platforms.remove(i);
                } else {
                    platforms.set(i, new PlatformCatalog(pc.platform(), pc.category(), pc.configFormat(), versions));
                }
                break;
            }

            save(new CatalogConfig(platforms));
            logger.debug("Removed catalog entry: {}/{}", platform, version);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Mark a specific version as recommended (clears recommended from all other
     * versions of the same platform).
     */
    public void setRecommended(String platform, String version) throws IOException {
        lock.writeLock().lock();
        try {
            var config = load();
            var platforms = new ArrayList<>(config.platforms());

            for (int i = 0; i < platforms.size(); i++) {
                var pc = platforms.get(i);
                if (!pc.platform().equalsIgnoreCase(platform)) continue;

                var versions = pc.versions().stream()
                        .map(v -> new VersionEntry(
                                v.version(),
                                v.downloadUrl(),
                                v.sha256(),
                                v.version().equals(version)))
                        .toList();
                platforms.set(i, new PlatformCatalog(pc.platform(), pc.category(), pc.configFormat(), versions));
                break;
            }

            save(new CatalogConfig(platforms));
            logger.debug("Set recommended: {}/{}", platform, version);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private CatalogConfig load() throws IOException {
        return YamlConfigLoader.mapper().readValue(catalogFile.toFile(), CatalogConfig.class);
    }

    private void save(CatalogConfig config) throws IOException {
        YamlConfigLoader.mapper().writeValue(catalogFile.toFile(), config);
    }

    private void ensureFile() throws IOException {
        if (Files.exists(catalogFile)) return;
        Files.createDirectories(catalogFile.getParent());
        YamlConfigLoader.mapper().writeValue(catalogFile.toFile(), new CatalogConfig(List.of()));
        logger.debug("Created empty catalog config at {}", catalogFile);
    }
}
