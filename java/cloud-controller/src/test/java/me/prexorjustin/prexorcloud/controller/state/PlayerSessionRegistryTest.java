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
}
