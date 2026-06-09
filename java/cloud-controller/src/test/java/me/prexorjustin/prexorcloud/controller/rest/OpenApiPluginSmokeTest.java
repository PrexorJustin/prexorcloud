package me.prexorjustin.prexorcloud.controller.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validates that the annotation-processor output is on the classpath and
 * carries the expected operation surface. The runtime serving of the spec
 * (RestServer's manual {@code /openapi} handler) reads exactly this
 * resource, so this is the same source the live endpoint exposes.
 *
 * <p>The community {@code javalin-openapi-plugin} 6.7.0-1 + community
 * {@code javalin-swagger-plugin} 6.7.0-1 register their routes against
 * Javalin 6's plugin SPI, not Javalin 7's; that's why RestServer wires
 * the spec endpoint manually rather than via {@code OpenApiPlugin}.
 */
@DisplayName("OpenAPI annotation-processor output")
class OpenApiPluginSmokeTest {

    @Test
    @DisplayName("classpath carries a well-formed spec covering routed endpoints")
    void classpathSpecIsWellFormed() throws Exception {
        try (var stream = RestServer.class.getResourceAsStream("/openapi-plugin/openapi-default.json")) {
            assertNotNull(stream, "annotation processor output must be on classpath");
            JsonNode spec = new ObjectMapper().readTree(stream);
            assertEquals("3.0.3", spec.get("openapi").asText(), "spec should be OpenAPI 3.0.3");

            JsonNode paths = spec.get("paths");
            assertNotNull(paths, "spec must carry paths");
            // Smoke level — assert several annotation-processor-emitted
            // operations made it into the spec. Picked representative
            // endpoints from each of the route families.
            assertTrue(paths.has("/api/v1/overview"), "spec should include /api/v1/overview");
            assertTrue(paths.has("/api/v1/roles"), "spec should include /api/v1/roles");
            assertTrue(paths.has("/api/v1/services/{id}/console"), "spec should include the SSE console stub");
            assertTrue(paths.has("/api/plugin/metrics"), "spec should include the plugin metrics endpoint");
            assertTrue(
                    paths.has("/api/v1/modules/platform/upload"), "spec should include the platform upload endpoint");

            // Sanity-check the rough operation count. Fails open with a wide
            // band so this test doesn't churn every time we add one route.
            int operationCount = 0;
            for (JsonNode methods : paths) {
                operationCount += methods.size();
            }
            assertTrue(
                    operationCount >= 150 && operationCount <= 300,
                    "operation count should be in the 150-300 range, got " + operationCount);
        }
    }
}
