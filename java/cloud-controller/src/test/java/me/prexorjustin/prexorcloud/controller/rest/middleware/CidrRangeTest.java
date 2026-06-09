package me.prexorjustin.prexorcloud.controller.rest.middleware;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

final class CidrRangeTest {

    @Test
    void parsesIPv4WithPrefix() throws Exception {
        var range = CidrRange.parse("10.0.0.0/8");
        assertTrue(range.contains(InetAddress.getByName("10.5.5.5")));
        assertTrue(range.contains(InetAddress.getByName("10.255.255.255")));
        assertFalse(range.contains(InetAddress.getByName("11.0.0.0")));
        assertTrue(range.isIpv4());
        assertEquals(8, range.prefixLength());
    }

    @Test
    void parsesIPv4HostRouteWithoutSlash() throws Exception {
        var range = CidrRange.parse("192.168.1.42");
        assertTrue(range.contains(InetAddress.getByName("192.168.1.42")));
        assertFalse(range.contains(InetAddress.getByName("192.168.1.43")));
        assertEquals(32, range.prefixLength());
    }

    @Test
    void parsesIPv6WithPrefix() throws Exception {
        var range = CidrRange.parse("fd00::/8");
        assertTrue(range.contains(InetAddress.getByName("fd00::1")));
        assertTrue(range.contains(InetAddress.getByName("fdff::dead:beef")));
        assertFalse(range.contains(InetAddress.getByName("fe00::1")));
        assertFalse(range.isIpv4());
        assertEquals(8, range.prefixLength());
    }

    @Test
    void parsesIPv6HostRouteWithoutSlash() throws Exception {
        var range = CidrRange.parse("::1");
        assertTrue(range.contains(InetAddress.getByName("::1")));
        assertFalse(range.contains(InetAddress.getByName("::2")));
        assertEquals(128, range.prefixLength());
    }

    @Test
    void zeroPrefixMatchesAll() throws Exception {
        var v4 = CidrRange.parse("0.0.0.0/0");
        assertTrue(v4.contains(InetAddress.getByName("1.2.3.4")));
        assertTrue(v4.contains(InetAddress.getByName("255.255.255.255")));
        var v6 = CidrRange.parse("::/0");
        assertTrue(v6.contains(InetAddress.getByName("::1")));
        assertTrue(v6.contains(InetAddress.getByName("fe80::1")));
    }

    @Test
    void fullPrefixIsSingleAddress() throws Exception {
        var range = CidrRange.parse("172.16.0.1/32");
        assertTrue(range.contains(InetAddress.getByName("172.16.0.1")));
        assertFalse(range.contains(InetAddress.getByName("172.16.0.2")));
    }

    @Test
    void ipv4AndIpv6NeverCrossMatch() throws Exception {
        // A pure v6 address should never match a v4 CIDR (note: Java's InetAddress
        // normalises IPv4-mapped ::ffff: addresses to Inet4Address, so testing with
        // those wouldn't actually exercise the cross-family guard).
        var v4 = CidrRange.parse("192.168.0.0/16");
        assertFalse(v4.contains(InetAddress.getByName("2001:db8::1")));
        var v6 = CidrRange.parse("::/0");
        assertFalse(v6.contains(InetAddress.getByName("192.168.0.1")));
    }

    @Test
    void canonicalisesLowBits() {
        // 1.2.3.4/24 should match the same range as 1.2.3.0/24
        var a = CidrRange.parse("1.2.3.4/24");
        var b = CidrRange.parse("1.2.3.0/24");
        assertEquals(a, b);
    }

    @Test
    void rejectsBadPrefix() {
        assertThrows(IllegalArgumentException.class, () -> CidrRange.parse("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> CidrRange.parse("10.0.0.0/-1"));
        assertThrows(IllegalArgumentException.class, () -> CidrRange.parse("::/129"));
    }

    @Test
    void rejectsBadFormat() {
        assertThrows(IllegalArgumentException.class, () -> CidrRange.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CidrRange.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CidrRange.parse("not-an-address/8"));
        assertThrows(IllegalArgumentException.class, () -> CidrRange.parse("10.0.0.0/abc"));
    }

    @Test
    void nullAddressDoesNotMatch() {
        assertFalse(CidrRange.parse("0.0.0.0/0").contains(null));
    }
}
