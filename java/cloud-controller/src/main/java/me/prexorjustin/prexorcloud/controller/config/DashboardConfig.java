package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Toggle for the in-controller dashboard SPA. {@code enabled=false} skips
 * static-file serving (operators who run the dashboard on a separate VPS via
 * {@code prexorctl setup --component=dashboard} typically set this so the
 * controller doesn't also serve a stale bundle).
 * <p>
 * The {@code path} previously exposed here is intentionally not configurable —
 * the controller serves from a fixed {@code dashboard/} directory under its
 * install root. Leftover {@code path:} entries in older {@code controller.yml}
 * files are ignored on load.
 */
public record DashboardConfig(@JsonProperty("enabled") boolean enabled) {

    public DashboardConfig() {
        this(true);
    }
}
