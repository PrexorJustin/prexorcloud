package me.prexorjustin.prexorcloud.modules.example.rest.dto;

import java.time.Instant;
import java.util.UUID;

/** Request body for {@code POST /session/end} (rest ingress alternative). */
public record SessionEndRequest(UUID sessionId, Instant quitAt, long durationMs) {}
