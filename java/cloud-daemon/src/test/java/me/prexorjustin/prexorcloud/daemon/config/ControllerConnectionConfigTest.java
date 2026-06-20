package me.prexorjustin.prexorcloud.daemon.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Covers the controller seed-list resolution: how {@code host}/{@code grpcPort} and the
 * {@code endpoints} list combine into the ordered, de-duplicated dial list the daemon rotates
 * through, plus {@code "host:port"} parsing and back-compat for pre-seed-list configs.
 */
final class ControllerConnectionConfigTest {

    // --- ControllerEndpoint.parse ---

    @Test
    void parsesHostAndPort() {
        var e = ControllerEndpoint.parse("10.0.0.7:9091", 9090);
        assertEquals("10.0.0.7", e.host());
        assertEquals(9091, e.port());
    }

    @Test
    void parseFallsBackToDefaultPortWhenAbsent() {
        assertEquals(9090, ControllerEndpoint.parse("ctrl-1", 9090).port());
        assertEquals(9090, ControllerEndpoint.parse("ctrl-1:", 9090).port());
        assertEquals("ctrl-1", ControllerEndpoint.parse("ctrl-1", 9090).host());
    }

    @Test
    void parseRejectsMalformedInput() {
        assertNull(ControllerEndpoint.parse(null, 9090));
        assertNull(ControllerEndpoint.parse("", 9090));
        assertNull(ControllerEndpoint.parse("   ", 9090));
        assertNull(ControllerEndpoint.parse(":9090", 9090));
        assertNull(ControllerEndpoint.parse("host:notaport", 9090));
        assertNull(ControllerEndpoint.parse("host:0", 9090));
        assertNull(ControllerEndpoint.parse("host:99999", 9090));
    }

    @Test
    void detectsLoopbackAndWildcard() {
        assertTrue(new ControllerEndpoint("127.0.0.1", 9090).isLoopbackOrWildcard());
        assertTrue(new ControllerEndpoint("localhost", 9090).isLoopbackOrWildcard());
        assertTrue(new ControllerEndpoint("0.0.0.0", 9090).isLoopbackOrWildcard());
        assertTrue(new ControllerEndpoint("::1", 9090).isLoopbackOrWildcard());
        assertFalse(new ControllerEndpoint("10.0.0.7", 9090).isLoopbackOrWildcard());
        assertFalse(new ControllerEndpoint("ctrl-1", 9090).isLoopbackOrWildcard());
    }

    // --- resolvedEndpoints ---

    @Test
    void singleHostResolvesToOneEndpoint() {
        var cfg = new ControllerConnectionConfig("ctrl-1", 9090);
        assertEquals(List.of(new ControllerEndpoint("ctrl-1", 9090)), cfg.resolvedEndpoints());
    }

    @Test
    void defaultConfigResolvesToLoopback() {
        // No endpoints configured → the single (default) host is still used, loopback or not.
        assertEquals(
                List.of(new ControllerEndpoint("127.0.0.1", 9090)),
                new ControllerConnectionConfig().resolvedEndpoints());
    }

    @Test
    void explicitHostIsKeptFirstThenEndpointsUnioned() {
        var cfg = new ControllerConnectionConfig("ctrl-1", 9090, List.of("ctrl-2:9090", "ctrl-3:9090"));
        assertEquals(
                List.of(
                        new ControllerEndpoint("ctrl-1", 9090),
                        new ControllerEndpoint("ctrl-2", 9090),
                        new ControllerEndpoint("ctrl-3", 9090)),
                cfg.resolvedEndpoints());
    }

    @Test
    void defaultedLoopbackHostIsDroppedWhenEndpointsGiven() {
        // host left at the 127.0.0.1 default but endpoints listed → don't waste the first dial on localhost.
        var cfg = new ControllerConnectionConfig("127.0.0.1", 9090, List.of("ctrl-1:9091", "ctrl-2:9091"));
        assertEquals(
                List.of(new ControllerEndpoint("ctrl-1", 9091), new ControllerEndpoint("ctrl-2", 9091)),
                cfg.resolvedEndpoints());
    }

    @Test
    void deduplicatesOverlappingHostAndEndpoints() {
        var cfg = new ControllerConnectionConfig("ctrl-1", 9090, List.of("ctrl-1:9090", "ctrl-2:9090"));
        assertEquals(
                List.of(new ControllerEndpoint("ctrl-1", 9090), new ControllerEndpoint("ctrl-2", 9090)),
                cfg.resolvedEndpoints());
    }

    @Test
    void endpointsInheritGrpcPortWhenPortOmitted() {
        var cfg = new ControllerConnectionConfig("ctrl-1", 9091, List.of("ctrl-2", "ctrl-3"));
        assertEquals(
                List.of(
                        new ControllerEndpoint("ctrl-1", 9091),
                        new ControllerEndpoint("ctrl-2", 9091),
                        new ControllerEndpoint("ctrl-3", 9091)),
                cfg.resolvedEndpoints());
    }

    @Test
    void unparseableEndpointsAreSkippedNotFatal() {
        var cfg = new ControllerConnectionConfig("ctrl-1", 9090, List.of("", "host:bad", "ctrl-2:9090"));
        assertEquals(
                List.of(new ControllerEndpoint("ctrl-1", 9090), new ControllerEndpoint("ctrl-2", 9090)),
                cfg.resolvedEndpoints());
    }

    @Test
    void allUnparseableEndpointsWithLoopbackHostFallsBackToHost() {
        var cfg = new ControllerConnectionConfig("127.0.0.1", 9090, List.of("", ":bad"));
        assertEquals(List.of(new ControllerEndpoint("127.0.0.1", 9090)), cfg.resolvedEndpoints());
    }

    @Test
    void nullEndpointsNormalizeToEmptyList() {
        var cfg = new ControllerConnectionConfig("ctrl-1", 9090, null);
        assertTrue(cfg.endpoints().isEmpty());
        assertEquals(List.of(new ControllerEndpoint("ctrl-1", 9090)), cfg.resolvedEndpoints());
    }
}
