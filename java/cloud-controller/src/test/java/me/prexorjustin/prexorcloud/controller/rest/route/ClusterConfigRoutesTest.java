package me.prexorjustin.prexorcloud.controller.rest.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClusterConfigRoutes")
class ClusterConfigRoutesTest {

    @Test
    @DisplayName("masks top-level sensitive paths and walks into nested maps")
    void masksKnownSensitivePaths() {
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("jwtSecret", "supersecret");
        security.put("jwtPreviousSecrets", List.of("old1", "old2"));
        security.put("jwtExpirationMinutes", 60);
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("uri", "mongodb://prod-cluster:27017");
        database.put("database", "prexorcloud");
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("security", security);
        patch.put("database", database);
        patch.put("corsAllowList", List.of("https://dashboard.example.com"));

        Map<String, Object> masked = ClusterConfigRoutes.maskPatch(patch, "");

        @SuppressWarnings("unchecked")
        Map<String, Object> maskedSecurity = (Map<String, Object>) masked.get("security");
        assertEquals("***", maskedSecurity.get("jwtSecret"));
        assertEquals("***", maskedSecurity.get("jwtPreviousSecrets"));
        assertEquals(60, maskedSecurity.get("jwtExpirationMinutes"));

        @SuppressWarnings("unchecked")
        Map<String, Object> maskedDatabase = (Map<String, Object>) masked.get("database");
        assertEquals("***", maskedDatabase.get("uri"));
        assertEquals("prexorcloud", maskedDatabase.get("database"));

        assertInstanceOf(List.class, masked.get("corsAllowList"));
    }

    @Test
    @DisplayName("preserves shape when no sensitive paths are present")
    void leavesInsensitivePatchUntouched() {
        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("requestsPerMinute", 240);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("rateLimit", rateLimit);

        Map<String, Object> masked = ClusterConfigRoutes.maskPatch(patch, "");

        assertEquals(patch, masked);
    }

    @Test
    @DisplayName("only masks at the exact registered path, not at deeper nestings of the same key name")
    void doesNotMaskUnrelatedKeysSharingName() {
        // A theoretical custom module might put `uri` under a non-sensitive parent.
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("uri", "https://webhook.example.com");
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("webhookAlerts", module);

        Map<String, Object> masked = ClusterConfigRoutes.maskPatch(patch, "");

        @SuppressWarnings("unchecked")
        Map<String, Object> maskedModule = (Map<String, Object>) masked.get("webhookAlerts");
        assertEquals("https://webhook.example.com", maskedModule.get("uri"));
    }
}
