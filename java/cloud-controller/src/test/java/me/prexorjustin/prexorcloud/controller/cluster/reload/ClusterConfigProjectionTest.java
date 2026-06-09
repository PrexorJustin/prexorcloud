package me.prexorjustin.prexorcloud.controller.cluster.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClusterConfigProjection")
class ClusterConfigProjectionTest {

    private static ClusterConfigVersion version(int v, int parent, Map<String, Object> patch) {
        return new ClusterConfigVersion(v, parent, "tester", Instant.EPOCH, patch, "reason");
    }

    @Test
    @DisplayName("empty when no version is active")
    void emptyWhenInactive() {
        assertTrue(ClusterConfigProjection.fold(List.of(), 0).isEmpty());
        assertTrue(ClusterConfigProjection.fold(List.of(version(1, 0, Map.of("a", 1))), 0)
                .isEmpty());
    }

    @Test
    @DisplayName("deep-merges a linear patch chain, later patches winning on scalars")
    void deepMergesLinearChain() {
        var v1 = version(
                1,
                0,
                Map.of(
                        "http", Map.of("cors", Map.of("allowedOrigins", List.of("https://a"))),
                        "security", Map.of("jwtSecret", "first", "jwtExpirationMinutes", 60)));
        // v2 only touches the cors origins; jwtSecret must survive from v1.
        var v2 = version(2, 1, Map.of("http", Map.of("cors", Map.of("allowedOrigins", List.of("https://b")))));

        Map<String, Object> folded = ClusterConfigProjection.fold(List.of(v1, v2), 2);

        assertEquals(List.of("https://b"), origins(folded));
        @SuppressWarnings("unchecked")
        Map<String, Object> security = (Map<String, Object>) folded.get("security");
        assertEquals("first", security.get("jwtSecret"));
        assertEquals(60, security.get("jwtExpirationMinutes"));
    }

    @Test
    @DisplayName("follows the parent chain, not version order, so a rollback+repatch is correct")
    void followsParentChainAcrossRollback() {
        // 1: origins=[a]; 2: origins=[b]; operator rolls back active->1; then patches
        // again parented on 1 producing version 3 with origins=[c]. Folding by version
        // number (1,2,3) would wrongly fold in v2's [b]; folding the parent chain
        // 3->1 yields [c].
        var v1 = version(1, 0, Map.of("http", Map.of("cors", Map.of("allowedOrigins", List.of("a")))));
        var v2 = version(2, 1, Map.of("http", Map.of("cors", Map.of("allowedOrigins", List.of("b")))));
        var v3 = version(3, 1, Map.of("http", Map.of("cors", Map.of("allowedOrigins", List.of("c")))));

        Map<String, Object> folded = ClusterConfigProjection.fold(List.of(v1, v2, v3), 3);

        assertEquals(List.of("c"), origins(folded));
    }

    @Test
    @DisplayName("a rollback active pointer projects the config as of that version")
    void rollbackPointerProjectsEarlierVersion() {
        var v1 = version(1, 0, Map.of("http", Map.of("cors", Map.of("allowedOrigins", List.of("a")))));
        var v2 = version(2, 1, Map.of("http", Map.of("cors", Map.of("allowedOrigins", List.of("b")))));

        // active rolled back to 1
        Map<String, Object> folded = ClusterConfigProjection.fold(List.of(v1, v2), 1);

        assertEquals(List.of("a"), origins(folded));
    }

    @SuppressWarnings("unchecked")
    private static Object origins(Map<String, Object> folded) {
        Map<String, Object> http = (Map<String, Object>) folded.get("http");
        Map<String, Object> cors = (Map<String, Object>) http.get("cors");
        return cors.get("allowedOrigins");
    }
}
