package me.prexorjustin.prexorcloud.controller.catalog;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfig.PlatformCatalog;

/**
 * Persistence interface for the software catalog.
 */
public interface CatalogStore {

    List<CatalogConfigLoader.CatalogEntry> getAll() throws IOException;

    List<CatalogConfigLoader.CatalogEntry> getByPlatform(String platform) throws IOException;

    Optional<PlatformCatalog> getPlatform(String platform) throws IOException;

    boolean addEntry(
            String platform, String category, String configFormat, String version, String downloadUrl, String sha256)
            throws IOException;

    void updateEntry(String platform, String oldVersion, String newVersion, String downloadUrl, String sha256)
            throws IOException;

    void removeEntry(String platform, String version) throws IOException;

    void setRecommended(String platform, String version) throws IOException;
}
