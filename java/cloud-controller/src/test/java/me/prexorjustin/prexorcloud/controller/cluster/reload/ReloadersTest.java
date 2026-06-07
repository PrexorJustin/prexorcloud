package me.prexorjustin.prexorcloud.controller.cluster.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.config.RateLimitingConfig;
import me.prexorjustin.prexorcloud.controller.rest.middleware.CorsAllowList;
import me.prexorjustin.prexorcloud.controller.rest.middleware.RateLimitMiddleware;
import me.prexorjustin.prexorcloud.security.jwt.JwtManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("cluster_config reloaders")
class ReloadersTest {

    private static Map<String, Object> cors(List<String> origins) {
        return Map.of("http", Map.of("cors", Map.of("allowedOrigins", origins)));
    }

    @Test
    @DisplayName("CorsAllowListReloader replaces the live list, including removals")
    void corsReplacesIncludingRemovals() {
        var allowList = new CorsAllowList(List.of("https://old", "https://stays"));
        new CorsAllowListReloader(allowList).onClusterConfig(cors(List.of("https://stays", "https://new")));

        assertEquals(List.of("https://stays", "https://new"), allowList.snapshot());
        assertFalse(allowList.allows("https://old"));
        assertTrue(allowList.allows("https://new"));
    }

    @Test
    @DisplayName("CorsAllowListReloader keeps current list when the patch would empty it")
    void corsRefusesEmpty() {
        var allowList = new CorsAllowList(List.of("https://keep"));
        new CorsAllowListReloader(allowList).onClusterConfig(cors(List.of()));
        assertEquals(List.of("https://keep"), allowList.snapshot());
    }

    @Test
    @DisplayName("CorsAllowListReloader is a no-op when the config has no cors slice")
    void corsNoSliceNoOp() {
        var allowList = new CorsAllowList(List.of("https://keep"));
        new CorsAllowListReloader(allowList).onClusterConfig(Map.of("security", Map.of("jwtSecret", "x")));
        assertEquals(List.of("https://keep"), allowList.snapshot());
    }

    @Test
    @DisplayName("RateLimitReloader swaps the live thresholds")
    void rateLimitReconfigures() {
        var limiter = new RateLimitMiddleware(new RateLimitingConfig(100, 300, false));
        var reloader = new RateLimitReloader(limiter);

        reloader.onClusterConfig(Map.of(
                "security",
                Map.of(
                        "rateLimiting",
                        Map.of("perIpPerMinute", 5, "perUserPerMinute", 7, "failOpenOnRedisError", true))));

        // The thresholds are now (5, 7, failOpen=true): re-applying the same values is a
        // no-op, while applying the original defaults registers as a change.
        assertFalse(
                limiter.reconfigure(new RateLimitingConfig(5, 7, true)), "reloaded values should already be active");
        assertTrue(limiter.reconfigure(new RateLimitingConfig(100, 300, false)), "different values should change");
    }

    @Test
    @DisplayName("RateLimitReloader floors non-positive limits back to defaults (can't disable limiting)")
    void rateLimitFloorsGarbage() {
        var limiter = new RateLimitMiddleware(new RateLimitingConfig(100, 300, false));
        var reloader = new RateLimitReloader(limiter);
        // perIpPerMinute=0 -> RateLimitingConfig floors to 100, so reconfigure() reports no change.
        reloader.onClusterConfig(Map.of("security", Map.of("rateLimiting", Map.of("perIpPerMinute", 0))));
        assertFalse(limiter.reconfigure(new RateLimitingConfig(100, 300, false)));
    }

    @Test
    @DisplayName("JwtSecretReloader rotates the active key and keeps the old one acceptable")
    void jwtRotatesWithOverlap() {
        String secretA = JwtManager.generateSecret();
        String secretB = JwtManager.generateSecret();
        var manager = new JwtManager(secretA, 60);
        String tokenFromA = manager.issue("admin", "ADMIN");

        new JwtSecretReloader(manager, secretA).onClusterConfig(Map.of("security", Map.of("jwtSecret", secretB)));

        // New tokens sign with B and validate...
        assertTrue(manager.validate(manager.issue("admin", "ADMIN")).isPresent());
        // ...and tokens signed with the now-previous A still validate through the overlap window.
        assertTrue(manager.validate(tokenFromA).isPresent());
    }

    @Test
    @DisplayName("JwtSecretReloader ignores a blank secret and an unchanged secret")
    void jwtIgnoresBlankAndUnchanged() {
        String secretA = JwtManager.generateSecret();
        var manager = new JwtManager(secretA, 60);
        int before = manager.acceptableKeyCount();
        var reloader = new JwtSecretReloader(manager, secretA);

        reloader.onClusterConfig(Map.of("security", Map.of("jwtSecret", "")));
        reloader.onClusterConfig(Map.of("security", Map.of("jwtSecret", secretA)));

        assertEquals(before, manager.acceptableKeyCount());
    }
}
