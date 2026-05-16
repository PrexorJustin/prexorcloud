package me.prexorjustin.prexorcloud.api.module.frontend;

import java.util.List;

/**
 * Describes a module's frontend bundle. When present inside a module JAR, the
 * controller extracts the frontend assets and serves them to the dashboard.
 */
public record FrontendManifest(
        int version,
        String displayName,
        String entry,
        String css,
        String icon,
        List<String> permissions,
        List<FrontendRoute> routes,
        List<String> events) {

    public FrontendManifest {
        if (version < 1) throw new IllegalArgumentException("Manifest version must be >= 1");
        if (entry == null || entry.isBlank()) throw new IllegalArgumentException("Frontend entry is required");
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("Display name is required");
        if (permissions == null) permissions = List.of();
        if (routes == null) routes = List.of();
        if (events == null) events = List.of();
    }
}
