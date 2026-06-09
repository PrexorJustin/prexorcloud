package me.prexorjustin.prexorcloud.controller.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates {@link ControllerConfig} at startup. Collects all errors before
 * failing, so the operator can fix all issues in one pass.
 */
public final class ConfigValidator {

    private static final Logger logger = LoggerFactory.getLogger(ConfigValidator.class);

    private ConfigValidator() {}

    /**
     * Validate the given config. Throws if any errors are found.
     *
     * @throws IllegalStateException
     *             with all validation errors concatenated
     */
    public static void validate(ControllerConfig config) {
        List<String> errors = new ArrayList<>();

        validateRuntime(config, errors);

        // HTTP
        validatePort(config.http().port(), "http.port", errors);

        // gRPC
        validatePort(config.grpc().port(), "grpc.port", errors);

        if (config.http().port() == config.grpc().port()) {
            errors.add("http.port and grpc.port must be different (both are "
                    + config.http().port() + ")");
        }

        // Security
        if (config.security().jwtExpirationMinutes() < 1) {
            errors.add("security.jwtExpirationMinutes must be >= 1");
        }
        if (config.security().jwtExpirationMinutes() > 43200) { // 30 days
            errors.add("security.jwtExpirationMinutes should not exceed 43200 (30 days)");
        }

        // Rate limiting
        var rl = config.security().rateLimiting();
        if (rl.perIpPerMinute() < 1) {
            errors.add("security.rateLimiting.perIpPerMinute must be >= 1");
        }
        if (rl.perUserPerMinute() < 1) {
            errors.add("security.rateLimiting.perUserPerMinute must be >= 1");
        }

        // Lockout
        var lockout = config.security().lockout();
        if (lockout.maxAttempts() < 1) {
            errors.add("security.lockout.maxAttempts must be >= 1");
        }
        if (lockout.windowSeconds() < 1) {
            errors.add("security.lockout.windowSeconds must be >= 1");
        }
        if (lockout.lockoutSeconds() < 1) {
            errors.add("security.lockout.lockoutSeconds must be >= 1");
        }

        // Database
        if (config.database().uri() == null || config.database().uri().isBlank()) {
            errors.add("database.uri must be configured");
        }
        if (config.redis() != null
                && (config.redis().uri() == null || config.redis().uri().isBlank())) {
            errors.add("redis.uri must not be blank when redis is configured");
        }
        if (config.runtime().production() && config.redis() == null) {
            errors.add("redis.uri must be configured when runtime.profile=production");
        }

        // Module signing policy
        var signing = config.modules().signing();
        boolean signingRequired = signing.requiredOrDefault(config.runtime().production());
        if (signingRequired) {
            String trustRoot = signing.trustRoot();
            if (trustRoot == null || trustRoot.isBlank()) {
                if (config.runtime().production()) {
                    errors.add("modules.signing.trustRoot must be configured when runtime.profile=production"
                            + " (or set modules.signing.required=false to opt out)");
                } else {
                    errors.add("modules.signing.trustRoot must be configured when modules.signing.required=true");
                }
            }
        }
        var rekor = signing.rekor();
        if (rekor != null && rekor.policy() != ModuleSigningConfig.RekorConfig.Policy.DISABLED) {
            if (signing.mode() != ModuleSigningConfig.Mode.COSIGN_BUNDLE) {
                errors.add("modules.signing.rekor.policy=" + rekor.policy()
                        + " requires modules.signing.mode=COSIGN_BUNDLE");
            }
            if (rekor.publicKey() == null || rekor.publicKey().isBlank()) {
                errors.add("modules.signing.rekor.publicKey must be configured when policy=" + rekor.policy());
            }
        }

        // Scheduler
        if (config.scheduler().evaluationIntervalSeconds() < 1) {
            errors.add("scheduler.evaluationIntervalSeconds must be >= 1");
        }

        // Heartbeat
        if (config.heartbeat().intervalMs() < 1000) {
            errors.add("heartbeat.intervalMs must be >= 1000");
        }
        if (config.heartbeat().missedThreshold() < 1) {
            errors.add("heartbeat.missedThreshold must be >= 1");
        }

        // CORS origins
        for (String origin : config.http().cors().allowedOrigins()) {
            if (!origin.startsWith("http://") && !origin.startsWith("https://")) {
                errors.add(
                        "cors.allowedOrigins: invalid origin '" + origin + "' (must start with http:// or https://)");
            }
        }

        if (!errors.isEmpty()) {
            logger.error("Configuration validation failed with {} error(s):", errors.size());
            for (String error : errors) {
                logger.error("  - {}", error);
            }
            throw new IllegalStateException("Invalid configuration — fix the errors above in controller.yml");
        }

        logger.debug("Configuration validation passed");
    }

    private static void validateRuntime(ControllerConfig config, List<String> errors) {
        RuntimeConfig runtime = config.runtime();
        if (!runtime.supported()) {
            errors.add("runtime.profile must be one of [development, production], got: " + runtime.profile());
        }
    }

    private static void validatePort(int port, String name, List<String> errors) {
        if (port < 1 || port > 65535) {
            errors.add(name + " must be between 1 and 65535, got: " + port);
        }
    }
}
