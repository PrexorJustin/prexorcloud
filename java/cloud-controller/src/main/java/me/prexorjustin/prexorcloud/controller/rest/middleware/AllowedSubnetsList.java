package me.prexorjustin.prexorcloud.controller.rest.middleware;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe mutable allow-list of CIDR ranges that gate inbound REST + gRPC
 * traffic. Loopback ({@code 127.0.0.0/8} and {@code ::1/128}) always passes
 * regardless of configuration — otherwise the controller's own health checks,
 * the local CLI, and SSH-tunneled access would break.
 * <p>
 * Reads are lock-free via a volatile snapshot of immutable {@link CidrRange}
 * lists. Writes are serialised by an intrinsic lock. Same pattern as
 * {@link CorsAllowList}.
 * <p>
 * The default {@code 0.0.0.0/0 + ::/0} from {@code controller.yml} means "wide
 * open" for backward compatibility on existing installs. Lock the cluster down
 * by replacing those with explicit per-daemon CIDRs (the daemon installer
 * auto-registers each new daemon's source IP as {@code /32}).
 */
public final class AllowedSubnetsList {

    private static final Logger logger = LoggerFactory.getLogger(AllowedSubnetsList.class);

    private static final CidrRange LOOPBACK_V4 = CidrRange.parse("127.0.0.0/8");
    private static final CidrRange LOOPBACK_V6 = CidrRange.parse("::1/128");

    private volatile List<CidrRange> ranges;

    public AllowedSubnetsList(List<String> initial) {
        this.ranges = parseAll(Objects.requireNonNullElseGet(initial, List::of));
    }

    /**
     * True if {@code address} matches any configured CIDR, or is loopback. Null
     * addresses are denied — the caller is responsible for resolving the source
     * IP before calling.
     */
    public boolean allows(InetAddress address) {
        if (address == null) return false;
        if (LOOPBACK_V4.contains(address) || LOOPBACK_V6.contains(address)) return true;
        if (address.isLoopbackAddress()) return true; // belt-and-suspenders for InetAddress oddities
        for (CidrRange range : ranges) {
            if (range.contains(address)) return true;
        }
        return false;
    }

    /**
     * Add a CIDR. Returns true if the list changed (i.e. the CIDR wasn't already
     * present). Throws {@link IllegalArgumentException} on malformed input.
     */
    public synchronized boolean add(String cidr) {
        CidrRange parsed = CidrRange.parse(cidr);
        if (ranges.contains(parsed)) return false;
        var next = new ArrayList<>(ranges);
        next.add(parsed);
        this.ranges = List.copyOf(next);
        logger.info("Allowed subnet added: {}", parsed.displayForm());
        return true;
    }

    /** Remove a CIDR. Returns true if it was present. */
    public synchronized boolean remove(String cidr) {
        CidrRange parsed = CidrRange.parse(cidr);
        if (!ranges.contains(parsed)) return false;
        var next = new ArrayList<>(ranges);
        next.remove(parsed);
        this.ranges = List.copyOf(next);
        logger.info("Allowed subnet removed: {}", parsed.displayForm());
        return true;
    }

    /** Display form of every configured CIDR, in insertion order. Immutable. */
    public List<String> snapshot() {
        return ranges.stream().map(CidrRange::displayForm).toList();
    }

    /**
     * True if the current list contains {@code 0.0.0.0/0} or {@code ::/0} — i.e.
     * the controller is wide open. Used by the daemon installer to decide whether
     * to recommend locking down after auto-registering a /32.
     */
    public boolean isWideOpen() {
        for (CidrRange range : ranges) {
            if (range.prefixLength() == 0) return true;
        }
        return false;
    }

    private static List<CidrRange> parseAll(List<String> raw) {
        var out = new ArrayList<CidrRange>(raw.size());
        for (String s : raw) {
            try {
                CidrRange parsed = CidrRange.parse(s);
                if (!out.contains(parsed)) out.add(parsed);
            } catch (IllegalArgumentException e) {
                logger.warn("Ignoring invalid CIDR in allowedSubnets: {} ({})", s, e.getMessage());
            }
        }
        return List.copyOf(out);
    }
}
