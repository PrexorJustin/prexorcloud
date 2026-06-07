package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PlayerSessionRegistryTest {

    @Test
    void backendUpdatePreservesKnownProxyInstance() {
        var registry = new PlayerSessionRegistry();
        var uuid = UUID.randomUUID();

        var proxyReport = registry.addReportedByProxy(uuid, "Steve", "lobby-1", "lobby", "proxy-1");
        var backendReport = registry.addReportedByBackend(uuid, "Steve", "backend-1", "survival");

        assertTrue(proxyReport.created());
        assertFalse(backendReport.created());
        assertEquals("proxy-1", backendReport.player().proxyInstanceId());
        assertEquals("backend-1", backendReport.player().instanceId());
        assertEquals("survival", backendReport.player().group());
    }

    @Test
    void explicitBedrockEditionIsHonoredEvenForJavaShapedUuid() {
        var registry = new PlayerSessionRegistry();
        // A non-Floodgate Geyser player has an ordinary Java-shaped UUID; only the sidecar knows
        // it is Bedrock, so the explicit edition must win over UUID-based detection.
        var javaShaped = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

        var report = registry.addReportedByProxy(javaShaped, "BedrockSteve", "lobby-1", "lobby", "geyser-1", "bedrock");

        assertEquals("bedrock", report.player().edition());
    }

    @Test
    void blankEditionFallsBackToUuidDetection() {
        var registry = new PlayerSessionRegistry();
        var floodgateShaped = new UUID(0L, 12345L);

        var report = registry.addReportedByProxy(floodgateShaped, "FloodgateSteve", "lobby-1", "lobby", "proxy-1", "");

        assertEquals("bedrock", report.player().edition());
    }
}
