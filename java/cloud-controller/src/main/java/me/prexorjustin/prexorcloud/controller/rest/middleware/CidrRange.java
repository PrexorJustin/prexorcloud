package me.prexorjustin.prexorcloud.controller.rest.middleware;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable IPv4 or IPv6 CIDR range. Use {@link #parse(String)} to construct;
 * matching is via {@link #contains(InetAddress)}.
 * <p>
 * The matcher compares the first {@code prefixLength} bits of the candidate
 * address against the canonical network address (low bits of the parsed input
 * are masked off, so {@code 1.2.3.4/24} and {@code 1.2.3.0/24} are equivalent).
 * Mismatched address families never match ({@code 192.168.0.0/16} never matches
 * an IPv6 address, even {@code ::ffff:192.168.0.1}).
 */
public final class CidrRange {

    private final byte[] networkBytes;
    private final int prefixLength;
    private final String displayForm;

    private CidrRange(byte[] networkBytes, int prefixLength, String displayForm) {
        this.networkBytes = networkBytes;
        this.prefixLength = prefixLength;
        this.displayForm = displayForm;
    }

    /**
     * Parse a CIDR string like {@code "10.0.0.0/8"} or {@code "fd00::/8"}. Bare
     * addresses (no slash) are treated as {@code /32} (IPv4) or {@code /128} (IPv6).
     *
     * @throws IllegalArgumentException
     *             on malformed input, unknown host, or out-of-range prefix.
     */
    public static CidrRange parse(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            throw new IllegalArgumentException("CIDR cannot be blank");
        }
        String trimmed = cidr.trim();
        String addressPart;
        Integer parsedPrefix; // null = no slash, user wants host route at parsed family's full prefix
        int slash = trimmed.indexOf('/');
        if (slash < 0) {
            addressPart = trimmed;
            parsedPrefix = null;
        } else {
            addressPart = trimmed.substring(0, slash);
            try {
                parsedPrefix = Integer.parseInt(trimmed.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR prefix: " + trimmed, e);
            }
        }

        InetAddress parsed;
        try {
            parsed = InetAddress.getByName(addressPart);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid CIDR address: " + addressPart, e);
        }

        byte[] addrBytes = parsed.getAddress();
        int maxPrefix = addrBytes.length * 8;
        int prefixLength = parsedPrefix == null ? maxPrefix : parsedPrefix;
        if (prefixLength < 0 || prefixLength > maxPrefix) {
            throw new IllegalArgumentException(
                    "Prefix length " + prefixLength + " out of range [0," + maxPrefix + "] for " + cidr);
        }

        byte[] networkBytes = maskNetworkBytes(addrBytes, prefixLength);
        return new CidrRange(networkBytes, prefixLength, trimmed);
    }

    /** True when {@code address} falls inside this CIDR range. */
    public boolean contains(InetAddress address) {
        if (address == null) return false;
        byte[] candidate = address.getAddress();
        if (candidate.length != networkBytes.length) return false; // different family

        int fullBytes = prefixLength / 8;
        int remainderBits = prefixLength % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != networkBytes[i]) return false;
        }
        if (remainderBits == 0) return true;
        int mask = 0xFF << (8 - remainderBits);
        return (candidate[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
    }

    /** Original input string, preserved for round-trips into YAML / logs. */
    public String displayForm() {
        return displayForm;
    }

    public int prefixLength() {
        return prefixLength;
    }

    public boolean isIpv4() {
        return networkBytes.length == 4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CidrRange other)) return false;
        return prefixLength == other.prefixLength && Arrays.equals(networkBytes, other.networkBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(networkBytes), prefixLength);
    }

    @Override
    public String toString() {
        return displayForm;
    }

    private static byte[] maskNetworkBytes(byte[] addrBytes, int prefixLength) {
        byte[] out = addrBytes.clone();
        int totalBits = out.length * 8;
        for (int bit = prefixLength; bit < totalBits; bit++) {
            int byteIdx = bit / 8;
            int bitInByte = 7 - (bit % 8);
            out[byteIdx] &= (byte) ~(1 << bitInByte);
        }
        return out;
    }
}
