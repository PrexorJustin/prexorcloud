package me.prexorjustin.prexorcloud.common.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CidrMatcher")
class CidrMatcherTest {

    @Nested
    @DisplayName("IPv4 matching")
    class IPv4Matching {

        @Test
        @DisplayName("Matches IP within /24 subnet")
        void matchesWithin24() {
            var matcher = new CidrMatcher(List.of("192.168.1.0/24"));
            assertTrue(matcher.matches("192.168.1.0"));
            assertTrue(matcher.matches("192.168.1.1"));
            assertTrue(matcher.matches("192.168.1.255"));
        }

        @Test
        @DisplayName("Rejects IP outside /24 subnet")
        void rejectsOutside24() {
            var matcher = new CidrMatcher(List.of("192.168.1.0/24"));
            assertFalse(matcher.matches("192.168.2.1"));
            assertFalse(matcher.matches("10.0.0.1"));
        }

        @Test
        @DisplayName("Matches exact /32 host")
        void matchesExactHost() {
            var matcher = new CidrMatcher(List.of("10.0.0.5/32"));
            assertTrue(matcher.matches("10.0.0.5"));
            assertFalse(matcher.matches("10.0.0.6"));
        }

        @Test
        @DisplayName("Matches with /16 prefix")
        void matches16Prefix() {
            var matcher = new CidrMatcher(List.of("172.16.0.0/16"));
            assertTrue(matcher.matches("172.16.0.1"));
            assertTrue(matcher.matches("172.16.255.255"));
            assertFalse(matcher.matches("172.17.0.1"));
        }

        @Test
        @DisplayName("Matches with /0 prefix (any address)")
        void matchesAny() {
            var matcher = new CidrMatcher(List.of("0.0.0.0/0"));
            assertTrue(matcher.matches("1.2.3.4"));
            assertTrue(matcher.matches("255.255.255.255"));
        }

        @Test
        @DisplayName("Without prefix length, defaults to /32")
        void defaultPrefix() {
            var matcher = new CidrMatcher(List.of("10.0.0.1"));
            assertTrue(matcher.matches("10.0.0.1"));
            assertFalse(matcher.matches("10.0.0.2"));
        }

        @Test
        @DisplayName("Non-byte-aligned prefix (/20) works correctly")
        void nonByteAlignedPrefix() {
            var matcher = new CidrMatcher(List.of("10.0.0.0/20"));
            assertTrue(matcher.matches("10.0.0.1"));
            assertTrue(matcher.matches("10.0.15.255")); // 10.0.00001111.11111111
            assertFalse(matcher.matches("10.0.16.0")); // 10.0.00010000.00000000
        }
    }

    @Nested
    @DisplayName("IPv6 matching")
    class IPv6Matching {

        @Test
        @DisplayName("Matches IPv6 within /64 prefix")
        void matchesWithin64() {
            var matcher = new CidrMatcher(List.of("fd00::/64"));
            assertTrue(matcher.matches("fd00::1"));
            assertTrue(matcher.matches("fd00::ffff"));
        }

        @Test
        @DisplayName("Rejects IPv6 outside prefix")
        void rejectsOutside() {
            var matcher = new CidrMatcher(List.of("fd00::/64"));
            assertFalse(matcher.matches("fd01::1"));
        }

        @Test
        @DisplayName("Matches loopback ::1 with /128")
        void matchesLoopback() {
            var matcher = new CidrMatcher(List.of("::1/128"));
            assertTrue(matcher.matches("::1"));
            assertFalse(matcher.matches("::2"));
        }
    }

    @Nested
    @DisplayName("Multiple CIDRs")
    class MultipleCidrs {

        @Test
        @DisplayName("Matches if any CIDR matches")
        void matchesAnyEntry() {
            var matcher = new CidrMatcher(List.of("10.0.0.0/8", "192.168.0.0/16"));
            assertTrue(matcher.matches("10.1.2.3"));
            assertTrue(matcher.matches("192.168.1.1"));
            assertFalse(matcher.matches("172.16.0.1"));
        }

        @Test
        @DisplayName("Empty CIDR list matches nothing")
        void emptyCidrList() {
            var matcher = new CidrMatcher(List.of());
            assertFalse(matcher.matches("10.0.0.1"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Invalid IP address returns false")
        void invalidIpReturnsFalse() {
            var matcher = new CidrMatcher(List.of("10.0.0.0/8"));
            assertFalse(matcher.matches("not-an-ip"));
        }

        @Test
        @DisplayName("IPv4 address does not match IPv6 CIDR")
        void ipv4DoesNotMatchIpv6() {
            var matcher = new CidrMatcher(List.of("fd00::/64"));
            assertFalse(matcher.matches("10.0.0.1"));
        }

        @Test
        @DisplayName("Invalid CIDR throws IllegalArgumentException")
        void invalidCidr() {
            assertThrows(IllegalArgumentException.class, () -> new CidrMatcher(List.of("not-a-cidr/24")));
        }
    }
}
