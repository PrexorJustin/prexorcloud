package me.prexorjustin.prexorcloud.common.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InstanceIdGenerator")
class InstanceIdGeneratorTest {

    @Nested
    @DisplayName("Dynamic ID generation")
    class Dynamic {

        @Test
        @DisplayName("First ID for empty group is group-1")
        void firstIdIsOne() {
            String id = InstanceIdGenerator.generateDynamic("lobby", Set.of());
            assertEquals("lobby-1", id);
        }

        @Test
        @DisplayName("Sequential IDs are group-1, group-2, group-3")
        void sequential() {
            Set<String> existing = new HashSet<>();
            for (int i = 1; i <= 3; i++) {
                String id = InstanceIdGenerator.generateDynamic("lobby", existing);
                assertEquals("lobby-" + i, id);
                existing.add(id);
            }
        }

        @Test
        @DisplayName("Gap filling: if 1 and 3 exist, next is 2")
        void gapFilling() {
            Set<String> existing = Set.of("lobby-1", "lobby-3");
            String id = InstanceIdGenerator.generateDynamic("lobby", existing);
            assertEquals("lobby-2", id);
        }

        @Test
        @DisplayName("Works with hyphenated group names")
        void hyphenatedGroup() {
            String id = InstanceIdGenerator.generateDynamic("bedwars-solo", Set.of());
            assertEquals("bedwars-solo-1", id);
        }

        @Test
        @DisplayName("Ignores non-numeric suffixes from old hex IDs")
        void ignoresHexSuffixes() {
            Set<String> existing = Set.of("lobby-a7f3", "lobby-ff00");
            String id = InstanceIdGenerator.generateDynamic("lobby", existing);
            assertEquals("lobby-1", id);
        }

        @Test
        @DisplayName("Generates many unique IDs without collision")
        void bulkUniqueness() {
            Set<String> existing = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                String id = InstanceIdGenerator.generateDynamic("test", existing);
                assertTrue(existing.add(id), "Duplicate ID generated: " + id);
            }
            assertEquals(100, existing.size());
        }
    }

    @Nested
    @DisplayName("Static instance IDs")
    class Static {

        @Test
        @DisplayName("Without names: returns group-1 through group-N")
        void numberedIds() {
            List<String> ids = InstanceIdGenerator.staticInstanceIds("lobby", 3, List.of());
            assertEquals(List.of("lobby-1", "lobby-2", "lobby-3"), ids);
        }

        @Test
        @DisplayName("With staticInstanceNames: returns those verbatim")
        void namedIds() {
            List<String> ids = InstanceIdGenerator.staticInstanceIds("hub", 2, List.of("hub-main", "hub-vip"));
            assertEquals(List.of("hub-main", "hub-vip"), ids);
        }

        @Test
        @DisplayName("Empty names list falls back to numbered")
        void emptyNamesFallback() {
            List<String> ids = InstanceIdGenerator.staticInstanceIds("proxy", 1, List.of());
            assertEquals(List.of("proxy-1"), ids);
        }

        @Test
        @DisplayName("Returned list is immutable")
        void immutableResult() {
            List<String> ids = InstanceIdGenerator.staticInstanceIds("lobby", 2, List.of());
            assertThrows(UnsupportedOperationException.class, () -> ids.add("lobby-3"));
        }
    }
}
