package me.prexorjustin.prexorcloud.modules.example.rest.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import me.prexorjustin.prexorcloud.modules.example.data.Session;

/** Response shape for {@code GET /player/{uuid}}. */
public record PlayerResponse(
        UUID playerId, long totalMs, int sessionCount, Instant lastSeen, List<Session> recentSessions) {}
