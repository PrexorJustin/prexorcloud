package me.prexorjustin.prexorcloud.controller.group;

import java.io.IOException;
import java.util.List;

import me.prexorjustin.prexorcloud.api.module.Version;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfigLoader;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;

/**
 * Resolves a group's runtime target against the controller catalog.
 */
public final class GroupRuntimeResolver {

    public record Resolution(
            GroupRuntimeTarget target, String category, String configFormat, String downloadUrl, String sha256) {}

    private GroupRuntimeResolver() {}

    public static Resolution resolve(GroupConfig group, CatalogStore catalogStore) {
        GroupRuntimeTarget fallbackTarget = group.runtimeTarget();
        if (catalogStore == null || group.platform() == null || group.platform().isBlank()) {
            return new Resolution(fallbackTarget, "", "", "", "");
        }

        try {
            List<CatalogConfigLoader.CatalogEntry> entries = catalogStore.getByPlatform(group.platform());
            CatalogConfigLoader.CatalogEntry selected = select(entries, group.platformVersion());
            if (selected == null) {
                return new Resolution(fallbackTarget, "", "", "", "");
            }
            GroupRuntimeFamily family = "PROXY".equalsIgnoreCase(selected.category())
                    ? GroupRuntimeFamily.PROXY
                    : GroupRuntimeFamily.SERVER;
            return new Resolution(
                    new GroupRuntimeTarget(group.platform(), selected.version(), family),
                    selected.category(),
                    selected.configFormat(),
                    selected.downloadUrl(),
                    selected.sha256());
        } catch (IOException e) {
            throw new IllegalStateException("failed to resolve runtime target for platform " + group.platform(), e);
        }
    }

    private static CatalogConfigLoader.CatalogEntry select(
            List<CatalogConfigLoader.CatalogEntry> entries, String platformVersion) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        if (platformVersion != null && !platformVersion.isBlank()) {
            CatalogConfigLoader.CatalogEntry exact = entries.stream()
                    .filter(entry -> platformVersion.equals(entry.version()))
                    .max(GroupRuntimeResolver::comparePriority)
                    .orElse(null);
            if (exact != null) {
                return exact;
            }
        }
        return entries.stream().max(GroupRuntimeResolver::comparePriority).orElse(null);
    }

    private static int comparePriority(CatalogConfigLoader.CatalogEntry left, CatalogConfigLoader.CatalogEntry right) {
        int recommended = Boolean.compare(left.recommended(), right.recommended());
        if (recommended != 0) {
            return recommended;
        }

        int version = compareVersion(left.version(), right.version());
        if (version != 0) {
            return version;
        }

        int rawVersion = left.version().compareToIgnoreCase(right.version());
        if (rawVersion != 0) {
            return rawVersion;
        }

        int downloadUrl = left.downloadUrl().compareTo(right.downloadUrl());
        if (downloadUrl != 0) {
            return downloadUrl;
        }

        return left.sha256().compareTo(right.sha256());
    }

    private static int compareVersion(String left, String right) {
        try {
            return Version.parse(left).compareTo(Version.parse(right));
        } catch (IllegalArgumentException _) {
            return left.compareToIgnoreCase(right);
        }
    }
}
