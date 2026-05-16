package me.prexorjustin.prexorcloud.common.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Matches IP addresses against CIDR subnet notation. Supports both IPv4 and
 * IPv6.
 */
public final class CidrMatcher {

    private final List<CidrEntry> entries;

    public CidrMatcher(List<String> cidrs) {
        this.entries = cidrs.stream().map(CidrEntry::parse).toList();
    }

    public boolean matches(String ipAddress) {
        try {
            byte[] addrBytes = InetAddress.getByName(ipAddress).getAddress();
            return entries.stream().anyMatch(entry -> entry.matches(addrBytes));
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    private record CidrEntry(byte[] network, int prefixLength) {

        static CidrEntry parse(String cidr) {
            int slash = cidr.indexOf('/');
            String host = slash >= 0 ? cidr.substring(0, slash) : cidr;

            try {
                InetAddress addr = InetAddress.getByName(host);
                byte[] networkBytes = addr.getAddress();
                int prefix = slash >= 0 ? Integer.parseInt(cidr.substring(slash + 1)) : networkBytes.length * 8;
                return new CidrEntry(networkBytes, prefix);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid CIDR: " + cidr, e);
            }
        }

        boolean matches(byte[] address) {
            if (address.length != network.length) return false;

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) return false;
            }

            if (remainingBits > 0 && fullBytes < network.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                return (address[fullBytes] & mask) == (network[fullBytes] & mask);
            }

            return true;
        }
    }
}
