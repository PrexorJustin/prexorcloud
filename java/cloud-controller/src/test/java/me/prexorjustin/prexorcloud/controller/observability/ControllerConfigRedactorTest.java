package me.prexorjustin.prexorcloud.controller.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.controller.config.DatabaseConfig;
import me.prexorjustin.prexorcloud.controller.config.RateLimitingConfig;
import me.prexorjustin.prexorcloud.controller.config.RedisConfig;
import me.prexorjustin.prexorcloud.controller.config.SecurityControllerConfig;

import org.junit.jupiter.api.Test;

class ControllerConfigRedactorTest {

    private static ControllerConfig configWith(
            SecurityControllerConfig security, DatabaseConfig db, RedisConfig redis) {
        return new ControllerConfig(
                null, null, null, null, db, null, null, null, null, security, null, null, null, null, null, null, null,
                redis);
    }

    @Test
    void redactsSecurityFieldsWhenPresent() {
        var security = new SecurityControllerConfig(
                "super-secret-jwt",
                1440,
                "admin-pw-123",
                new RateLimitingConfig(),
                List.of("old-secret-1", "old-secret-2"));
        var config = configWith(security, null, null);

        var redacted = ControllerConfigRedactor.redact(config);

        assertEquals(
                ControllerConfigRedactor.REDACTED,
                redacted.path("security").path("jwtSecret").asText());
        assertEquals(
                ControllerConfigRedactor.REDACTED,
                redacted.path("security").path("initialAdminPassword").asText());
        var previous = redacted.path("security").path("jwtPreviousSecrets");
        assertTrue(previous.isArray(), "jwtPreviousSecrets must remain an array");
        assertEquals(2, previous.size(), "redactor must preserve count");
        assertEquals(ControllerConfigRedactor.REDACTED, previous.get(0).asText());
        assertEquals(ControllerConfigRedactor.REDACTED, previous.get(1).asText());
    }

    @Test
    void leavesNonSecretFieldsAlone() {
        var redacted = ControllerConfigRedactor.redact(configWith(null, null, null));

        assertTrue(redacted.has("http"));
        assertTrue(redacted.has("scheduler"));
        assertEquals(
                1440, redacted.path("security").path("jwtExpirationMinutes").asInt());
    }

    @Test
    void redactsMongoUriPasswordButKeepsHostAndUser() {
        var config = configWith(
                null, new DatabaseConfig("mongodb://admin:hunter2@mongo.internal:27017/prexor", "prexor"), null);
        var redacted = ControllerConfigRedactor.redact(config);
        String uri = redacted.path("database").path("uri").asText();
        assertFalse(uri.contains("hunter2"), "password must be removed: " + uri);
        assertTrue(uri.startsWith("mongodb://admin:"));
        assertTrue(uri.contains("@mongo.internal:27017"));
        assertTrue(uri.contains("REDACTED"));
    }

    @Test
    void redactsRedisUriPassword() {
        var config = configWith(null, null, new RedisConfig("redis://default:topsecret@valkey.internal:6379"));
        var redacted = ControllerConfigRedactor.redact(config);
        String uri = redacted.path("redis").path("uri").asText();
        assertFalse(uri.contains("topsecret"));
        assertTrue(uri.startsWith("redis://default:"));
        assertTrue(uri.contains("@valkey.internal:6379"));
    }

    @Test
    void uriWithoutPasswordIsLeftUnchanged() {
        assertEquals(
                "mongodb://localhost:27017", ControllerConfigRedactor.redactUriUserinfo("mongodb://localhost:27017"));
        assertEquals(
                "redis://valkey.internal:6379",
                ControllerConfigRedactor.redactUriUserinfo("redis://valkey.internal:6379"));
    }

    @Test
    void blankAndNullUriArePassedThrough() {
        assertEquals("", ControllerConfigRedactor.redactUriUserinfo(""));
        assertNull(ControllerConfigRedactor.redactUriUserinfo(null));
    }

    @Test
    void unparseableUriStillRedacts() {
        // Real-world URIs sometimes include unencoded special characters that URI parsing
        // rejects. The redactor must still strip the password component or diagnostics
        // would leak it.
        String hard = "mongodb://admin:p@ss^word!@host:27017/db";
        String redacted = ControllerConfigRedactor.redactUriUserinfo(hard);
        assertFalse(redacted.contains("p@ss^word!"), "raw password must not leak: " + redacted);
        assertTrue(redacted.contains("REDACTED"));
    }
}
