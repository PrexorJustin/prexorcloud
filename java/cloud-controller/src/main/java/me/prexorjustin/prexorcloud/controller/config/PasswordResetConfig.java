package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Password-reset block under {@link SecurityControllerConfig}. When
 * {@link #enabled()} is false the {@code /api/v1/auth/password-reset/*} routes
 * return 404 and no manager is wired into the controller.
 *
 * <p>{@code resetUrlBase} is the base URL the dashboard is reachable at — the
 * mailer appends {@code /auth/reset-password?token=...} to it. When blank,
 * tokens are still minted but the email body falls back to a relative path so
 * the operator can wire it up later.
 *
 * <p>{@link #smtp()} is optional: when {@code smtp.host} is blank, a {@code
 * LogMailer} is used that writes the reset link to the controller log. This
 * makes development and dry-run installs trivial — operators move to a real
 * mailer by setting {@code smtp.host}.
 */
public record PasswordResetConfig(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("tokenTtlMinutes") int tokenTtlMinutes,
        @JsonProperty("resetUrlBase") String resetUrlBase,
        @JsonProperty("smtp") SmtpConfig smtp) {

    public PasswordResetConfig {
        if (tokenTtlMinutes <= 0) tokenTtlMinutes = 30;
        if (resetUrlBase == null) resetUrlBase = "";
        if (smtp == null) smtp = new SmtpConfig();
    }

    public PasswordResetConfig() {
        this(false, 30, "", new SmtpConfig());
    }
}
