package me.prexorjustin.prexorcloud.controller.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SecurityControllerConfig(
        @JsonProperty("jwtSecret") String jwtSecret,
        @JsonProperty("jwtExpirationMinutes") int jwtExpirationMinutes,
        @JsonProperty("initialAdminPassword") String initialAdminPassword,
        @JsonProperty("rateLimiting") RateLimitingConfig rateLimiting,
        @JsonProperty("jwtPreviousSecrets") List<String> jwtPreviousSecrets,
        @JsonProperty("lockout") LockoutConfig lockout,
        @JsonProperty("passwordReset") PasswordResetConfig passwordReset) {

    public SecurityControllerConfig {
        if (jwtSecret == null) jwtSecret = "";
        if (jwtExpirationMinutes <= 0) jwtExpirationMinutes = 1440;
        if (initialAdminPassword == null) initialAdminPassword = "";
        if (rateLimiting == null) rateLimiting = new RateLimitingConfig();
        if (jwtPreviousSecrets == null) jwtPreviousSecrets = List.of();
        else jwtPreviousSecrets = List.copyOf(jwtPreviousSecrets);
        if (lockout == null) lockout = new LockoutConfig();
        if (passwordReset == null) passwordReset = new PasswordResetConfig();
    }

    public SecurityControllerConfig() {
        this("", 1440, "", new RateLimitingConfig(), List.of(), new LockoutConfig(), new PasswordResetConfig());
    }

    public SecurityControllerConfig(
            String jwtSecret, int jwtExpirationMinutes, String initialAdminPassword, RateLimitingConfig rateLimiting) {
        this(
                jwtSecret,
                jwtExpirationMinutes,
                initialAdminPassword,
                rateLimiting,
                List.of(),
                new LockoutConfig(),
                new PasswordResetConfig());
    }

    public SecurityControllerConfig(
            String jwtSecret,
            int jwtExpirationMinutes,
            String initialAdminPassword,
            RateLimitingConfig rateLimiting,
            List<String> jwtPreviousSecrets) {
        this(
                jwtSecret,
                jwtExpirationMinutes,
                initialAdminPassword,
                rateLimiting,
                jwtPreviousSecrets,
                new LockoutConfig(),
                new PasswordResetConfig());
    }

    public SecurityControllerConfig(
            String jwtSecret,
            int jwtExpirationMinutes,
            String initialAdminPassword,
            RateLimitingConfig rateLimiting,
            List<String> jwtPreviousSecrets,
            LockoutConfig lockout) {
        this(
                jwtSecret,
                jwtExpirationMinutes,
                initialAdminPassword,
                rateLimiting,
                jwtPreviousSecrets,
                lockout,
                new PasswordResetConfig());
    }
}
