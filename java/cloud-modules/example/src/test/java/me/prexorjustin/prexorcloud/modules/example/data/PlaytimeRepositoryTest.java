package me.prexorjustin.prexorcloud.modules.example.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;
import me.prexorjustin.prexorcloud.api.module.data.Update;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlaytimeRepository")
class PlaytimeRepositoryTest {

    @Mock
    ModuleDataStore store;

    PlaytimeRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PlaytimeRepository(store);
    }

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("Ensures both collections exist")
        void ensuresCollections() {
            verify(store).ensureCollection(PlaytimeRepository.SESSIONS);
            verify(store).ensureCollection(PlaytimeRepository.TOTALS);
        }

        @Test
        @DisplayName("Creates indexes on sessions and totals")
        void createsIndexes() {
            verify(store, org.mockito.Mockito.atLeast(5)).createIndex(any(String.class), any(IndexSpec.class));
        }
    }

    @Nested
    @DisplayName("openSession")
    class OpenSession {

        @Test
        @DisplayName("Inserts the session into the sessions collection")
        void insertsSession() {
            Session session = new Session(
                    UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-04-13T12:00:00Z"), null, 0L, "lobby-1");

            repo.openSession(session);

            verify(store).insertOne(PlaytimeRepository.SESSIONS, session);
        }
    }

    @Nested
    @DisplayName("closeSession")
    class CloseSession {

        @Test
        @DisplayName("Updates the matching session row with quitAt and durationMs")
        void updatesSession() {
            UUID sessionId = UUID.randomUUID();
            Instant quitAt = Instant.parse("2026-04-13T13:00:00Z");

            repo.closeSession(sessionId, quitAt, 3_600_000L);

            verify(store).updateOne(eq(PlaytimeRepository.SESSIONS), any(Query.class), any(Update.class));
        }
    }

    @Nested
    @DisplayName("recentSessions")
    class RecentSessions {

        @Test
        @DisplayName("Queries the sessions collection with the provided limit")
        void queriesSessions() {
            UUID playerId = UUID.randomUUID();
            Session stub = new Session(playerId, UUID.randomUUID(), Instant.now(), null, 0L, "lobby-1");
            when(store.find(
                            eq(PlaytimeRepository.SESSIONS),
                            any(Query.class),
                            any(Sort.class),
                            eq(10),
                            eq(Session.class)))
                    .thenReturn(List.of(stub));

            List<Session> result = repo.recentSessions(playerId, 10);

            assertEquals(1, result.size());
            assertSame(stub, result.get(0));
        }
    }

    @Nested
    @DisplayName("totalFor")
    class TotalFor {

        @Test
        @DisplayName("Returns empty when no totals row exists")
        void returnsEmpty() {
            UUID playerId = UUID.randomUUID();
            when(store.findOne(eq(PlaytimeRepository.TOTALS), any(Query.class), eq(TopEntry.class)))
                    .thenReturn(Optional.empty());

            assertTrue(repo.totalFor(playerId).isEmpty());
        }

        @Test
        @DisplayName("Returns the totals row when present")
        void returnsEntry() {
            UUID playerId = UUID.randomUUID();
            TopEntry entry = new TopEntry(playerId, 5_000L, 3, Instant.now());
            when(store.findOne(eq(PlaytimeRepository.TOTALS), any(Query.class), eq(TopEntry.class)))
                    .thenReturn(Optional.of(entry));

            assertEquals(Optional.of(entry), repo.totalFor(playerId));
        }
    }

    @Nested
    @DisplayName("top")
    class Top {

        @Test
        @DisplayName("Reads from the totals collection sorted descending")
        void readsTotals() {
            TopEntry a = new TopEntry(UUID.randomUUID(), 9_000L, 5, Instant.now());
            TopEntry b = new TopEntry(UUID.randomUUID(), 3_000L, 2, Instant.now());
            when(store.find(
                            eq(PlaytimeRepository.TOTALS),
                            any(Query.class),
                            any(Sort.class),
                            eq(10),
                            eq(TopEntry.class)))
                    .thenReturn(List.of(a, b));

            List<TopEntry> result = repo.top(10);

            assertEquals(List.of(a, b), result);
        }
    }

    @Nested
    @DisplayName("deleteOlderThan")
    class DeleteOlderThan {

        @Test
        @DisplayName("Deletes from the sessions collection and returns the count")
        void deletesSessions() {
            Instant cutoff = Instant.parse("2026-03-01T00:00:00Z");
            when(store.deleteMany(eq(PlaytimeRepository.SESSIONS), any(Query.class)))
                    .thenReturn(7);

            int deleted = repo.deleteOlderThan(cutoff);

            assertEquals(7, deleted);
            verify(store, never()).deleteMany(eq(PlaytimeRepository.TOTALS), any(Query.class));
        }
    }
}
