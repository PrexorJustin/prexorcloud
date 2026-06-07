package me.prexorjustin.prexorcloud.modules.example.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.modules.example.data.PlaytimeRepository;
import me.prexorjustin.prexorcloud.modules.example.data.Session;
import me.prexorjustin.prexorcloud.modules.example.data.TopEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlaytimeQueryServiceImpl")
class PlaytimeQueryServiceImplTest {

    @Mock
    PlaytimeRepository repo;

    PlaytimeQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PlaytimeQueryServiceImpl(repo);
    }

    @Nested
    @DisplayName("totalMs")
    class TotalMs {

        @Test
        @DisplayName("Returns zero when no totals row exists")
        void zeroWhenMissing() {
            UUID playerId = UUID.randomUUID();
            when(repo.totalFor(playerId)).thenReturn(Optional.empty());

            assertEquals(0L, service.totalMs(playerId));
        }

        @Test
        @DisplayName("Returns the TopEntry.totalMs when present")
        void delegatesToRepo() {
            UUID playerId = UUID.randomUUID();
            when(repo.totalFor(playerId)).thenReturn(Optional.of(new TopEntry(playerId, 42_000L, 3, Instant.now())));

            assertEquals(42_000L, service.totalMs(playerId));
        }
    }

    @Nested
    @DisplayName("top")
    class Top {

        @Test
        @DisplayName("Delegates directly to the repository")
        void delegates() {
            List<TopEntry> stub = List.of(new TopEntry(UUID.randomUUID(), 1L, 1, Instant.now()));
            when(repo.top(10)).thenReturn(stub);

            assertSame(stub, service.top(10));
            verify(repo).top(10);
        }
    }

    @Nested
    @DisplayName("latest")
    class Latest {

        @Test
        @DisplayName("Returns empty when the repository has no recent sessions")
        void emptyWhenNone() {
            UUID playerId = UUID.randomUUID();
            when(repo.recentSessions(eq(playerId), any(Integer.class))).thenReturn(List.of());

            assertTrue(service.latest(playerId).isEmpty());
        }

        @Test
        @DisplayName("Returns the first recent session when present")
        void returnsFirst() {
            UUID playerId = UUID.randomUUID();
            Session session = new Session(playerId, UUID.randomUUID(), Instant.now(), null, 0L, "lobby-1");
            when(repo.recentSessions(eq(playerId), any(Integer.class))).thenReturn(List.of(session));

            assertEquals(Optional.of(session), service.latest(playerId));
        }
    }
}
