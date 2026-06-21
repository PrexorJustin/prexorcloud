package me.prexorjustin.prexorcloud.daemon.config;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expands the configured controller seeds into concrete dial candidates, resolving DNS so an operator can
 * configure ONE stable name instead of every controller IP.
 *
 * <p>Resolution order, union'd + de-duplicated:
 * <ol>
 *   <li><b>SRV</b> ({@code controller.dnsSrv}) — preferred: carries host+port and is resolved via JNDI,
 *       which bypasses the JVM {@link InetAddress} positive cache entirely (the stale-IP-across-reconnects
 *       trap) and returns fresh records per lookup.</li>
 *   <li>The literal seeds from {@link ControllerConnectionConfig#resolvedEndpoints()}, where a non-IP
 *       {@code host} is A-record-expanded to one candidate <em>per address</em> (so one name &rarr; all
 *       controllers become rotation targets). IP literals pass through untouched.</li>
 * </ol>
 *
 * <p>Discovery is best-effort: any DNS failure degrades to the literal seeds (or keeps the name so the
 * gRPC dial can resolve it), and after the first connect the HandshakeAck self-sync replaces the seed with
 * the live member set. So DNS is strictly the cold-start hint, never the running source of truth.
 *
 * <p>The dial path uses {@code new InetSocketAddress(host, port)} (a deliberate direct-address path that
 * keeps the mTLS authority exactly {@code host:port}); we therefore expand to IP candidates here rather
 * than switching to a {@code dns:///} gRPC target, which would hand rotation to gRPC's load balancer and
 * fight the explicit leader-redirect logic.
 */
public final class ControllerSeedResolver {

    private static final Logger logger = LoggerFactory.getLogger(ControllerSeedResolver.class);

    /** The DNS seam — injectable so tests resolve deterministically without touching real DNS. */
    public interface DnsLookup {
        /** SRV targets for {@code serviceName} as host:port endpoints (priority asc, then weight desc). */
        List<ControllerEndpoint> lookupSrv(String serviceName);

        /** A-record addresses for {@code host} as IPv4-literal strings. */
        List<String> lookupHosts(String host);
    }

    private final DnsLookup dns;

    public ControllerSeedResolver() {
        this(new JndiDnsLookup());
    }

    public ControllerSeedResolver(DnsLookup dns) {
        this.dns = dns;
    }

    public List<ControllerEndpoint> resolve(ControllerConnectionConfig config) {
        var out = new LinkedHashSet<ControllerEndpoint>();

        String srv = config.dnsSrv();
        if (srv != null && !srv.isBlank()) {
            try {
                List<ControllerEndpoint> fromSrv = dns.lookupSrv(srv.trim());
                out.addAll(fromSrv);
                logger.info("Controller SRV '{}' resolved {} endpoint(s)", srv.trim(), fromSrv.size());
            } catch (RuntimeException e) {
                logger.warn("Controller SRV lookup '{}' failed -- using literal seeds: {}", srv.trim(), e.getMessage());
            }
        }

        for (ControllerEndpoint ep : config.resolvedEndpoints()) {
            if (isIpLiteral(ep.host())) {
                out.add(ep);
                continue;
            }
            try {
                List<String> ips = dns.lookupHosts(ep.host());
                if (ips.isEmpty()) {
                    out.add(ep); // keep the name; the gRPC dial resolves it itself (one record, cached)
                } else {
                    for (String ip : ips) {
                        out.add(new ControllerEndpoint(ip, ep.port()));
                    }
                }
            } catch (RuntimeException e) {
                logger.warn(
                        "DNS expansion of controller host '{}' failed -- keeping name: {}", ep.host(), e.getMessage());
                out.add(ep);
            }
        }

        if (out.isEmpty()) {
            return config.resolvedEndpoints();
        }
        return List.copyOf(out);
    }

    /** Rough IPv4/IPv6-literal check — avoids a pointless DNS lookup for an address that is already an IP. */
    static boolean isIpLiteral(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return true; // IPv6 literal (bracketed or not)
        }
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                dots++;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return dots == 3;
    }

    /** Default JDK-backed lookup: SRV via JNDI ({@code jdk.naming.dns}), A-records via {@link InetAddress}. */
    static final class JndiDnsLookup implements DnsLookup {

        @Override
        public List<ControllerEndpoint> lookupSrv(String serviceName) {
            var env = new Hashtable<String, String>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            InitialDirContext ctx = null;
            try {
                ctx = new InitialDirContext(env);
                Attributes attrs = ctx.getAttributes(serviceName, new String[] {"SRV"});
                Attribute srv = attrs.get("SRV");
                if (srv == null) {
                    return List.of();
                }
                // Each SRV value is "priority weight port target." (target carries a trailing dot).
                var recs = new ArrayList<SrvRec>();
                for (int i = 0; i < srv.size(); i++) {
                    String[] parts = String.valueOf(srv.get(i)).trim().split("\\s+");
                    if (parts.length < 4) {
                        continue;
                    }
                    try {
                        int priority = Integer.parseInt(parts[0]);
                        int weight = Integer.parseInt(parts[1]);
                        int port = Integer.parseInt(parts[2]);
                        String target =
                                parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
                        if (!target.isBlank() && port > 0 && port <= 65535) {
                            recs.add(new SrvRec(priority, weight, new ControllerEndpoint(target, port)));
                        }
                    } catch (NumberFormatException ignore) {
                        // skip a malformed SRV line rather than failing the whole lookup
                    }
                }
                recs.sort(Comparator.comparingInt(SrvRec::priority)
                        .thenComparing(Comparator.comparingInt(SrvRec::weight).reversed()));
                return recs.stream().map(SrvRec::endpoint).toList();
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                if (ctx != null) {
                    try {
                        ctx.close();
                    } catch (Exception ignore) {
                        // best effort
                    }
                }
            }
        }

        @Override
        public List<String> lookupHosts(String host) {
            try {
                var out = new ArrayList<String>();
                for (InetAddress a : InetAddress.getAllByName(host)) {
                    if (a instanceof Inet4Address) {
                        out.add(a.getHostAddress());
                    }
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        private record SrvRec(int priority, int weight, ControllerEndpoint endpoint) {}
    }
}
