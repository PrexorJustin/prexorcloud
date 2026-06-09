package me.prexorjustin.prexorcloud.modules.protocoltap.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PacketCountRepository")
class PacketCountRepositoryTest {

    @Mock
    private ModuleDataStore store;

    @Test
    @DisplayName("constructor ensures collection and creates the two indexes")
    void constructorPreparesCollection() {
        new PacketCountRepository(store);

        verify(store).ensureCollection(PacketCountRepository.OBSERVATIONS);
        verify(store, times(2)).createIndex(eq(PacketCountRepository.OBSERVATIONS), any(IndexSpec.class));
    }

    @Test
    @DisplayName("record() forwards the observation to insertOne")
    void recordForwardsToStore() {
        PacketCountRepository repo = new PacketCountRepository(store);
        PacketCount count = new PacketCount("lobby", "AsyncChat", 42L, 1700000000000L);

        repo.record(count);

        verify(store).insertOne(PacketCountRepository.OBSERVATIONS, count);
    }

    @Test
    @DisplayName("recent() with explicit group returns the store's hits")
    void recentReturnsStoreHits() {
        when(store.find(
                        eq(PacketCountRepository.OBSERVATIONS),
                        any(Query.class),
                        any(Sort.class),
                        eq(10),
                        eq(PacketCount.class)))
                .thenReturn(List.of(new PacketCount("lobby", "AsyncChat", 5L, 1L)));
        PacketCountRepository repo = new PacketCountRepository(store);

        var result = repo.recent("lobby", 10);

        assertEquals(1, result.size());
        assertEquals("lobby", result.get(0).group());
    }
}
