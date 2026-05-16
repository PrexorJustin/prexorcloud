package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Paste-share feature configuration — controls the {@code --share} workflow that
 * uploads redacted text artifacts (crash reports, log tails, diagnostics
 * bundles) to a pastebin and prints the resulting link.
 *
 * <p>
 * Defaults target <a href="https://pste.dev">pste.dev</a> with private pastes
 * and a short expiry. Sharing is always operator-invoked; redaction is
 * unconditional and applied server-side by {@code ShareService}.
 * </p>
 *
 * <p>
 * <b>Default posture:</b> {@code enabled=false} — operators opt in explicitly
 * before any artifact leaves the cluster. Burn-after-read is decided per-surface
 * by {@code ShareService.BurnDefault} ({@code SINGLE_READER} for crash + log
 * tails, {@code MULTI_READER} for diagnostics bundles); the previous
 * {@code defaultBurnAfterRead} global flag never had effect and has been removed
 * from the schema.
 * </p>
 */
public record ShareConfig(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("pasteUrl") String pasteUrl,
        @JsonProperty("pasteToken") String pasteToken,
        @JsonProperty("defaultExpiry") String defaultExpiry,
        @JsonProperty("defaultPrivate") boolean defaultPrivate,
        @JsonProperty("e2e") boolean e2e) {

    public ShareConfig {
        if (pasteUrl == null || pasteUrl.isBlank()) pasteUrl = "https://pste.dev";
        if (defaultExpiry == null || defaultExpiry.isBlank()) defaultExpiry = "1d";
    }

    public ShareConfig() {
        this(false, "https://pste.dev", null, "1d", true, false);
    }
}
