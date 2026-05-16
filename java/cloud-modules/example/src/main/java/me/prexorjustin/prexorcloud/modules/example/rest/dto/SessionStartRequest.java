package me.prexorjustin.prexorcloud.modules.example.rest.dto;

import java.time.Instant;
import java.util.UUID;

/** Request body for {@code POST /session/start} (rest ingress alternative). */
public record SessionStartRequest(UUID playerId, UUID sessionId, String serverName, Instant joinAt) {}
