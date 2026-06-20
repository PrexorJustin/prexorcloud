package me.prexorjustin.prexorcloud.daemon.config;

import java.util.Objects;

/**
 * A single controller dial target (host + gRPC port).
 *
 * <p>This is the immutable unit of the daemon's connection seed list: parsed from the
 * {@code controller.endpoints} strings in {@code daemon.yml}, rotated through by
 * {@code DaemonGrpcClient}, and swept by {@code BootstrapManager}. IPv6 literals are not
 * supported — controllers advertise IPv4 addresses or DNS names.</p>
 */
public record ControllerEndpoint(String host, int port) {

    public ControllerEndpoint {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("controller endpoint host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("controller endpoint port out of range: " + port);
        }
    }

    /**
     * Parse a {@code "host:port"} string. A missing or empty port (no colon, or a trailing colon)
     * falls back to {@code defaultPort}. Returns {@code null} — rather than throwing — for any
     * malformed input so callers can skip a bad config entry without failing the whole list.
     */
    public static ControllerEndpoint parse(String hostColonPort, int defaultPort) {
        if (hostColonPort == null) {
            return null;
        }
        String s = hostColonPort.trim();
        if (s.isEmpty()) {
            return null;
        }
        int colon = s.lastIndexOf(':');
        String host;
        int port;
        if (colon < 0) {
            host = s;
            port = defaultPort;
        } else if (colon == 0) {
            return null; // ":9090" — no host
        } else {
            host = s.substring(0, colon).trim();
            String portStr = s.substring(colon + 1).trim();
            if (portStr.isEmpty()) {
                port = defaultPort;
            } else {
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        if (host.isBlank() || port <= 0 || port > 65535) {
            return null;
        }
        return new ControllerEndpoint(host, port);
    }

    /**
     * Whether this endpoint's host is a loopback or wildcard address (localhost, 127.x, 0.0.0.0,
     * ::, ::1). Such addresses are never routable controller targets — they're filtered out of a
     * multi-endpoint seed list and out of cluster-advertised / persisted peer lists.
     */
    public boolean isLoopbackOrWildcard() {
        String h = host.toLowerCase();
        return h.equals("localhost")
                || h.equals("0.0.0.0")
                || h.equals("::")
                || h.equals("::1")
                || h.equals("[::]")
                || h.equals("[::1]")
                || h.startsWith("127.");
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
