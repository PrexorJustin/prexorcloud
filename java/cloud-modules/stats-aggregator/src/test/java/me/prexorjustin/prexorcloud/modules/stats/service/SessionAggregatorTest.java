package me.prexorjustin.prexorcloud.modules.stats.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.modules.stats.data.SessionRecord;
import me.prexorjustin.prexorcloud.modules.stats.data.StatsRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionAggregator")
class SessionAggregatorTest {

    @Mock
    StatsRepository repo;

    @Test
    @DisplayName("onJoin opens an upsert-shaped record with provided fields")
    void onJoinOpensSession() {
        SessionAggregator agg = new SessionAggregator(repo);
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant joinAt = Instant.parse("2026-04-13T12:00:00Z");

        agg.onJoin(playerId, "alice", sessionId, "lobby", "lobby-1", joinAt);

        ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
        verify(repo).openSession(captor.capture());
        SessionRecord r = captor.getValue();
        assertEquals(playerId, r.playerId());
        assertEquals(sessionId, r.sessionId());
        assertEquals("alice", r.playerName());
        assertEquals("lobby", r.group());
        assertEquals("lobby-1", r.instanceId());
        assertEquals(joinAt, r.joinAt());
        assertEquals(0L, r.durationMs());
    }

    @Test
    @DisplayName("onLeave returns NOT_FOUND when no session matches")
    void onLeaveNotFound() {
        SessionAggregator agg = new SessionAggregator(repo);
        UUID sessionId = UUID.randomUUID();
        when(repo.findSession(sessionId)).thenReturn(Optional.empty());

        var outcome = agg.onLeave(sessionId, Instant.now(), null);

        assertEquals(SessionAggregator.CloseOutcome.NOT_FOUND, outcome);
        verify(repo, never()).closeSession(any(UUID.class), any(Instant.class), anyLong());
    }

    @Test
    @DisplayName("onLeave computes duration from joinAt when not provided")
    void onLeaveComputesDuration() {
        SessionAggregator agg = new SessionAggregator(repo);
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant joinAt = Instant.parse("2026-04-13T12:00:00Z");
        Instant quitAt = Instant.parse("2026-04-13T12:30:00Z");
        SessionRecord existing = new SessionRecord(playerId, "alice", sessionId, "lobby", "lobby-1", joinAt, null, 0L);
        when(repo.findSession(sessionId)).thenReturn(Optional.of(existing));

        var outcome = agg.onLeave(sessionId, quitAt, null);

        assertEquals(SessionAggregator.CloseOutcome.CLOSED, outcome);
        verify(repo).closeSession(eq(sessionId), eq(quitAt), eq(30L * 60_000L));
    }

    @Test
    @DisplayName("onLeave honours caller-provided durationMs")
    void onLeaveProvidedDuration() {
        SessionAggregator agg = new SessionAggregator(repo);
        UUID sessionId = UUID.randomUUID();
        Instant joinAt = Instant.parse("2026-04-13T12:00:00Z");
        Instant quitAt = Instant.parse("2026-04-13T12:30:00Z");
        SessionRecord existing =
                new SessionRecord(UUID.randomUUID(), "alice", sessionId, "lobby", "lobby-1", joinAt, null, 0L);
        when(repo.findSession(sessionId)).thenReturn(Optional.of(existing));

        agg.onLeave(sessionId, quitAt, 12_345L);

        verify(repo).closeSession(eq(sessionId), eq(quitAt), eq(12_345L));
    }
}
