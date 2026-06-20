package me.prexorjustin.prexorcloud.daemon.config;

import java.util.LinkedHashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Where the daemon dials the controller.
 *
 * <p>For a single controller, set {@code host} + {@code grpcPort} (back-compatible). For an
 * HA cluster, list every controller in {@code endpoints} ({@code "host:port"} strings) so the
 * daemon can reach the cluster through any of them and survive one controller being down — the
 * existing redirect-to-leader then routes it to the current leader. The cluster also advertises
 * its live members at runtime, so the configured list is a bootstrap seed, not a fixed roster.</p>
 */
public record ControllerConnectionConfig(
        @JsonProperty("host") String host,
        @JsonProperty("grpcPort") int grpcPort,
        @JsonProperty("endpoints") List<String> endpoints) {

    public ControllerConnectionConfig {
        if (host == null) host = "127.0.0.1";
        if (grpcPort <= 0) grpcPort = 9090;
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    /** Single-endpoint convenience constructor (back-compat with pre-seed-list callers). */
    public ControllerConnectionConfig(String host, int grpcPort) {
        this(host, grpcPort, List.of());
    }

    public ControllerConnectionConfig() {
        this("127.0.0.1", 9090, List.of());
    }

    /**
     * The ordered, de-duplicated dial seed list. The single {@code host:grpcPort} comes first so an
     * explicitly-configured host is never silently dropped — except when an {@code endpoints} list is
     * given and the host is the loopback/wildcard default, which would only waste the first dial.
     * Unparseable {@code endpoints} entries are skipped. Never empty.
     */
    public List<ControllerEndpoint> resolvedEndpoints() {
        var seen = new LinkedHashSet<ControllerEndpoint>();
        var hostEndpoint = new ControllerEndpoint(host, grpcPort);
        if (endpoints.isEmpty() || !hostEndpoint.isLoopbackOrWildcard()) {
            seen.add(hostEndpoint);
        }
        for (String e : endpoints) {
            ControllerEndpoint parsed = ControllerEndpoint.parse(e, grpcPort);
            if (parsed != null) {
                seen.add(parsed);
            }
        }
        if (seen.isEmpty()) {
            // endpoints were all unparseable and the host was loopback — never return an empty list.
            seen.add(hostEndpoint);
        }
        return List.copyOf(seen);
    }
}
