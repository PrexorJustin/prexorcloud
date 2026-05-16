package me.prexorjustin.prexorcloud.controller.catalog;

import java.util.List;

/**
 * YAML-serializable catalog configuration.
 */
public record CatalogConfig(List<PlatformCatalog> platforms) {

    public CatalogConfig {
        if (platforms == null) platforms = List.of();
    }

    public record PlatformCatalog(String platform, String category, String configFormat, List<VersionEntry> versions) {

        public PlatformCatalog {
            if (platform == null) platform = "";
            if (category == null) category = "SERVER";
            if (versions == null) versions = List.of();
        }

        public boolean isProxy() {
            return "PROXY".equalsIgnoreCase(category);
        }
    }

    public record VersionEntry(String version, String downloadUrl, String sha256, boolean recommended) {

        public VersionEntry {
            if (version == null) version = "";
            if (downloadUrl == null) downloadUrl = "";
            if (sha256 == null) sha256 = "";
        }
    }
}
