package me.prexorjustin.prexorcloud.daemon.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ControllerSeedResolver")
class ControllerSeedResolverTest {

    private static ControllerSeedResolver.DnsLookup stub(
            Map<String, List<ControllerEndpoint>> srv, Map<String, List<String>> hosts) {
        return new ControllerSeedResolver.DnsLookup() {
            @Override
            public List<ControllerEndpoint> lookupSrv(String name) {
                return srv.getOrDefault(name, List.of());
            }

            @Override
            public List<String> lookupHosts(String host) {
                return hosts.getOrDefault(host, List.of());
            }
        };
    }

    @Test
    @DisplayName("SRV record expands to all targets")
    void srvRecordExpandsToAllTargets() {
        var dns = stub(
                Map.of(
                        "_c._tcp.x",
                        List.of(new ControllerEndpoint("10.0.0.6", 9090), new ControllerEndpoint("10.0.0.7", 9090))),
                Map.of());
        var cfg = new ControllerConnectionConfig("127.0.0.1", 9090, List.of(), "_c._tcp.x");
        var out = new ControllerSeedResolver(dns).resolve(cfg);
        assertTrue(out.contains(new ControllerEndpoint("10.0.0.6", 9090)));
        assertTrue(out.contains(new ControllerEndpoint("10.0.0.7", 9090)));
    }

    @Test
    @DisplayName("a DNS name host expands to one candidate per A record")
    void dnsNameHostExpandsToAllARecords() {
        var dns = stub(Map.of(), Map.of("controllers.internal", List.of("10.0.0.6", "10.0.0.7")));
        var cfg = new ControllerConnectionConfig("controllers.internal", 9090, List.of(), null);
        var out = new ControllerSeedResolver(dns).resolve(cfg);
        assertEquals(List.of(new ControllerEndpoint("10.0.0.6", 9090), new ControllerEndpoint("10.0.0.7", 9090)), out);
    }

    @Test
    @DisplayName("IP literals pass through without a DNS lookup")
    void ipLiteralsPassThroughWithoutDns() {
        var dns = stub(Map.of(), Map.of()); // any lookup would return empty
        var cfg = new ControllerConnectionConfig("10.0.0.3", 9090, List.of("10.0.0.6:9090"), null);
        var out = new ControllerSeedResolver(dns).resolve(cfg);
        assertEquals(List.of(new ControllerEndpoint("10.0.0.3", 9090), new ControllerEndpoint("10.0.0.6", 9090)), out);
    }

    @Test
    @DisplayName("an unresolvable name is kept so the candidate list is never empty")
    void keepsNameWhenDnsYieldsNothing() {
        var dns = stub(Map.of(), Map.of());
        var cfg = new ControllerConnectionConfig("controllers.internal", 9090, List.of(), null);
        var out = new ControllerSeedResolver(dns).resolve(cfg);
        assertEquals(List.of(new ControllerEndpoint("controllers.internal", 9090)), out);
    }

    @Test
    @DisplayName("detects IPv4/IPv6 literals vs names")
    void detectsIpLiterals() {
        assertTrue(ControllerSeedResolver.isIpLiteral("10.0.0.3"));
        assertTrue(ControllerSeedResolver.isIpLiteral("::1"));
        assertFalse(ControllerSeedResolver.isIpLiteral("controllers.internal"));
        assertFalse(ControllerSeedResolver.isIpLiteral("host"));
    }
}
