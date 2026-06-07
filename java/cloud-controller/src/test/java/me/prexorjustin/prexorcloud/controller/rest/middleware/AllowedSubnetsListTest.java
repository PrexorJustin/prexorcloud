package me.prexorjustin.prexorcloud.controller.rest.middleware;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.List;

import org.junit.jupiter.api.Test;

final class AllowedSubnetsListTest {

    @Test
    void allowsConfiguredCidr() throws Exception {
        var list = new AllowedSubnetsList(List.of("10.0.0.0/8"));
        assertTrue(list.allows(InetAddress.getByName("10.1.2.3")));
        assertFalse(list.allows(InetAddress.getByName("11.0.0.1")));
    }

    @Test
    void loopbackAlwaysAllowedEvenWithEmptyList() throws Exception {
        var list = new AllowedSubnetsList(List.of());
        assertTrue(list.allows(InetAddress.getByName("127.0.0.1")));
        assertTrue(list.allows(InetAddress.getByName("127.255.255.254")));
        assertTrue(list.allows(InetAddress.getByName("::1")));
    }

    @Test
    void emptyListDeniesNonLoopback() throws Exception {
        var list = new AllowedSubnetsList(List.of());
        assertFalse(list.allows(InetAddress.getByName("8.8.8.8")));
        assertFalse(list.allows(InetAddress.getByName("fe80::1")));
    }

    @Test
    void wideOpenAllowsEverything() throws Exception {
        var list = new AllowedSubnetsList(List.of("0.0.0.0/0", "::/0"));
        assertTrue(list.allows(InetAddress.getByName("203.0.113.1")));
        assertTrue(list.allows(InetAddress.getByName("2001:db8::1")));
        assertTrue(list.isWideOpen());
    }

    @Test
    void notWideOpenWhenAllPrefixesAreSpecific() throws Exception {
        var list = new AllowedSubnetsList(List.of("10.0.0.0/8", "192.168.0.0/16"));
        assertFalse(list.isWideOpen());
    }

    @Test
    void addIsIdempotent() {
        var list = new AllowedSubnetsList(List.of());
        assertTrue(list.add("10.0.0.0/8"));
        assertFalse(list.add("10.0.0.0/8"));
        assertEquals(List.of("10.0.0.0/8"), list.snapshot());
    }

    @Test
    void addCanonicalisesEquivalentCidrs() {
        var list = new AllowedSubnetsList(List.of());
        list.add("10.0.0.0/8");
        // 10.5.5.5/8 has the same network address after masking → considered equal
        assertFalse(list.add("10.5.5.5/8"));
    }

    @Test
    void removeReturnsFalseWhenAbsent() {
        var list = new AllowedSubnetsList(List.of());
        assertFalse(list.remove("10.0.0.0/8"));
        list.add("10.0.0.0/8");
        assertTrue(list.remove("10.0.0.0/8"));
        assertFalse(list.allows(asAddr("10.1.2.3")));
    }

    @Test
    void rejectsMalformedAddOrRemove() {
        var list = new AllowedSubnetsList(List.of());
        assertThrows(IllegalArgumentException.class, () -> list.add("nope"));
        assertThrows(IllegalArgumentException.class, () -> list.remove("nope"));
    }

    @Test
    void constructorIgnoresMalformedEntries() throws Exception {
        // Bad CIDRs in controller.yml shouldn't crash startup; they're logged and dropped.
        var list = new AllowedSubnetsList(List.of("10.0.0.0/8", "not-a-cidr", "fd00::/8"));
        assertTrue(list.allows(InetAddress.getByName("10.0.0.1")));
        assertTrue(list.allows(InetAddress.getByName("fd00::1")));
        assertEquals(List.of("10.0.0.0/8", "fd00::/8"), list.snapshot());
    }

    @Test
    void nullSourceAddressDenied() {
        var list = new AllowedSubnetsList(List.of("0.0.0.0/0"));
        assertFalse(list.allows(null));
    }

    private static InetAddress asAddr(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
