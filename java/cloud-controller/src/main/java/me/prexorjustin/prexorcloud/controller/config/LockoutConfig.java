package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Account-lockout policy. Disabled by setting {@code enabled=false}; otherwise
 * a user is locked for {@code lockoutSeconds} after {@code maxAttempts} failed
 * login attempts within {@code windowSeconds}.
 */
public record LockoutConfig(
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("maxAttempts") int maxAttempts,
        @JsonProperty("windowSeconds") int windowSeconds,
        @JsonProperty("lockoutSeconds") int lockoutSeconds) {

    public LockoutConfig {
        if (enabled == null) enabled = Boolean.TRUE;
        if (maxAttempts <= 0) maxAttempts = 5;
        if (windowSeconds <= 0) windowSeconds = 900;
        if (lockoutSeconds <= 0) lockoutSeconds = 900;
    }

    public LockoutConfig() {
        this(Boolean.TRUE, 5, 900, 900);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
