package me.prexorjustin.prexorcloud.controller.rest.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClusterSeedRoutes")
class ClusterSeedRoutesTest {

    @Test
    @DisplayName("freshSeedBase64 returns base64 of exactly 32 bytes")
    void freshSeedIs32Bytes() {
        String seed = ClusterSeedRoutes.freshSeedBase64();
        byte[] decoded = Base64.getDecoder().decode(seed);
        assertEquals(32, decoded.length, "cluster seed must be 32 bytes (HMAC-SHA256 keyspace)");
    }

    @Test
    @DisplayName("freshSeedBase64 produces different seeds on consecutive calls")
    void freshSeedsAreNotConstant() {
        String a = ClusterSeedRoutes.freshSeedBase64();
        String b = ClusterSeedRoutes.freshSeedBase64();
        assertNotEquals(a, b, "two seed rotations must produce different bytes");
    }
}
