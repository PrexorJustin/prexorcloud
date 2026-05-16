package me.prexorjustin.prexorcloud.modules.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModuleRouteRegistryTest {

    @Test
    void recordsAndResolvesByMethodAndTemplate() {
        var registry = new ModuleRouteRegistry();
        var registrar = registry.registrarFor("stats-aggregator");
        registrar.get("/players/top", (req, res) -> {});
        registrar.post("/sessions/join", (req, res) -> {});

        var topMatch = registry.resolve("stats-aggregator", "GET", "players/top");
        assertTrue(topMatch.isPresent());
        assertEquals("/players/top", topMatch.get().route().template());

        var joinMatch = registry.resolve("stats-aggregator", "POST", "sessions/join");
        assertTrue(joinMatch.isPresent());

        // Method mismatch and unknown subpath both miss.
        assertTrue(registry.resolve("stats-aggregator", "PUT", "players/top").isEmpty());
        assertTrue(
                registry.resolve("stats-aggregator", "GET", "players/missing").isEmpty());
    }

    @Test
    void capturesPathParams() {
        var registry = new ModuleRouteRegistry();
        registry.registrarFor("stats-aggregator").get("/players/{uuid}", (req, res) -> {});

        var match = registry.resolve("stats-aggregator", "GET", "players/abc-123");
        assertTrue(match.isPresent());
        assertEquals("abc-123", match.get().pathParams().get("uuid"));
    }

    @Test
    void leadingSlashOnSubpathIsAccepted() {
        var registry = new ModuleRouteRegistry();
        registry.registrarFor("m").get("/x", (req, res) -> {});

        // The dispatcher passes the wildcard as-is — both forms should resolve identically.
        assertTrue(registry.resolve("m", "GET", "x").isPresent());
        assertTrue(registry.resolve("m", "GET", "/x").isPresent());
    }

    @Test
    void clearRoutesDropsModuleEntries() {
        var registry = new ModuleRouteRegistry();
        registry.registrarFor("a").get("/x", (req, res) -> {});
        registry.registrarFor("b").get("/y", (req, res) -> {});

        registry.clearRoutes("a");

        assertTrue(registry.resolve("a", "GET", "x").isEmpty());
        assertTrue(registry.resolve("b", "GET", "y").isPresent());
    }

    @Test
    void noopHookSilentlyAcceptsRegistration() {
        // The default-wired hook (used when the controller doesn't supply a real registry)
        // must accept registrar calls without throwing — modules built against the API
        // should never see a NullPointerException because the controller wasn't wired.
        var registrar = ModuleRouteRegistry.NOOP_HOOK.registrarFor("any");
        assertNotNull(registrar);
        registrar.get("/x", (req, res) -> {});
        registrar.post("/y", (req, res) -> {});
        ModuleRouteRegistry.NOOP_HOOK.clearRoutes("any"); // no-op
    }
}
