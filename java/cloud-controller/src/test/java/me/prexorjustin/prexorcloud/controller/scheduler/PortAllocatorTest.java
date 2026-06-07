package me.prexorjustin.prexorcloud.controller.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PortAllocator")
class PortAllocatorTest {

    @Nested
    @DisplayName("Successful allocation")
    class SuccessfulAllocation {

        @Test
        @DisplayName("Allocates the lowest port in an empty range")
        void lowestPortWhenEmpty() {
            var result = PortAllocator.allocate(30000, 30010, Set.of());
            assertTrue(result.isPresent());
            assertEquals(30000, result.getAsInt());
        }

        @Test
        @DisplayName("Skips used ports and returns next available")
        void skipsUsedPorts() {
            var result = PortAllocator.allocate(30000, 30010, Set.of(30000, 30001));
            assertTrue(result.isPresent());
            assertEquals(30002, result.getAsInt());
        }

        @Test
        @DisplayName("Allocates last port in range when all others are used")
        void lastPortAvailable() {
            var result = PortAllocator.allocate(30000, 30002, Set.of(30000, 30001));
            assertTrue(result.isPresent());
            assertEquals(30002, result.getAsInt());
        }

        @Test
        @DisplayName("Single port range with no used ports")
        void singlePortRange() {
            var result = PortAllocator.allocate(25565, 25565, Set.of());
            assertTrue(result.isPresent());
            assertEquals(25565, result.getAsInt());
        }

        @Test
        @DisplayName("Ignores used ports outside the range")
        void ignoresOutOfRangePorts() {
            var result = PortAllocator.allocate(30000, 30005, Set.of(29999, 30006));
            assertTrue(result.isPresent());
            assertEquals(30000, result.getAsInt());
        }
    }

    @Nested
    @DisplayName("Range exhaustion")
    class RangeExhaustion {

        @Test
        @DisplayName("Returns empty when all ports in range are used")
        void allPortsUsed() {
            var result = PortAllocator.allocate(30000, 30002, Set.of(30000, 30001, 30002));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Returns empty when single port is used")
        void singlePortUsed() {
            var result = PortAllocator.allocate(25565, 25565, Set.of(25565));
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Allocates with scattered used ports")
        void scatteredUsedPorts() {
            var result = PortAllocator.allocate(30000, 30010, Set.of(30000, 30002, 30004));
            assertTrue(result.isPresent());
            assertEquals(30001, result.getAsInt());
        }

        @Test
        @DisplayName("Empty used ports set works correctly")
        void emptyUsedPortsSet() {
            var result = PortAllocator.allocate(1, 65535, Set.of());
            assertTrue(result.isPresent());
            assertEquals(1, result.getAsInt());
        }
    }
}
