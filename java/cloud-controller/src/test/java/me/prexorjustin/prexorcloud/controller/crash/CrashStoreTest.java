package me.prexorjustin.prexorcloud.controller.crash;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CrashStore")
class CrashStoreTest {

    private CrashStore store;

    @BeforeEach
    void setUp() {
        store = new CrashStore(3);
    }

    @Nested
    @DisplayName("Adding records")
    class AddRecords {

        @Test
        @DisplayName("Add a record and retrieve it")
        void addAndRetrieve() {
            var record = store.add("lobby-1", "lobby", "node-1", 1, "GENERAL_ERROR", List.of(), 5000);

            assertNotNull(record);
            assertTrue(record.id().startsWith("crash-"));
            assertEquals("lobby-1", record.instanceId());
            assertEquals("lobby", record.group());
            assertEquals("node-1", record.nodeId());
            assertEquals(1, record.exitCode());
            assertEquals("GENERAL_ERROR", record.classification());
            assertNotNull(record.crashedAt());
        }

        @Test
        @DisplayName("Generated IDs are unique")
        void uniqueIds() {
            var r1 = store.add("inst-1", "g", "n", 1, "X", List.of(), 0);
            var r2 = store.add("inst-2", "g", "n", 1, "X", List.of(), 0);
            assertNotEquals(r1.id(), r2.id());
        }

        @Test
        @DisplayName("Size reflects number of records")
        void sizeTracking() {
            assertEquals(0, store.size());
            store.add("i1", "g", "n", 1, "X", List.of(), 0);
            assertEquals(1, store.size());
            store.add("i2", "g", "n", 1, "X", List.of(), 0);
            assertEquals(2, store.size());
        }
    }

    @Nested
    @DisplayName("Ring buffer eviction")
    class RingBuffer {

        @Test
        @DisplayName("Evicts oldest record when at capacity")
        void evictionAtCapacity() {
            store.add("i1", "g", "n", 1, "X", List.of(), 0);
            store.add("i2", "g", "n", 1, "X", List.of(), 0);
            store.add("i3", "g", "n", 1, "X", List.of(), 0);
            assertEquals(3, store.size());

            store.add("i4", "g", "n", 1, "X", List.of(), 0);
            assertEquals(3, store.size());

            var all = store.getAll();
            assertTrue(
                    all.stream().noneMatch(r -> r.instanceId().equals("i1")),
                    "Oldest record (i1) should have been evicted");
            assertTrue(all.stream().anyMatch(r -> r.instanceId().equals("i4")), "Newest record (i4) should be present");
        }

        @Test
        @DisplayName("Capacity of 1 keeps only the latest record")
        void capacityOne() {
            var smallStore = new CrashStore(1);
            smallStore.add("i1", "g", "n", 1, "X", List.of(), 0);
            smallStore.add("i2", "g", "n", 1, "X", List.of(), 0);

            assertEquals(1, smallStore.size());
            assertEquals("i2", smallStore.getAll().getFirst().instanceId());
        }
    }

    @Nested
    @DisplayName("Filtering")
    class Filtering {

        @Test
        @DisplayName("getByGroup returns only matching group records")
        void filterByGroup() {
            store.add("i1", "lobby", "n1", 1, "X", List.of(), 0);
            store.add("i2", "bedwars", "n1", 1, "X", List.of(), 0);
            store.add("i3", "lobby", "n2", 1, "X", List.of(), 0);

            var lobbyRecords = store.getByGroup("lobby");
            assertEquals(2, lobbyRecords.size());
            assertTrue(lobbyRecords.stream().allMatch(r -> r.group().equals("lobby")));
        }

        @Test
        @DisplayName("getByNode returns only matching node records")
        void filterByNode() {
            store.add("i1", "lobby", "node-1", 1, "X", List.of(), 0);
            store.add("i2", "lobby", "node-2", 1, "X", List.of(), 0);

            var node1Records = store.getByNode("node-1");
            assertEquals(1, node1Records.size());
            assertEquals("node-1", node1Records.getFirst().nodeId());
        }

        @Test
        @DisplayName("getAll returns immutable copy")
        void immutableCopy() {
            store.add("i1", "g", "n", 1, "X", List.of(), 0);
            var all = store.getAll();
            assertThrows(UnsupportedOperationException.class, () -> all.add(null));
        }
    }
}
