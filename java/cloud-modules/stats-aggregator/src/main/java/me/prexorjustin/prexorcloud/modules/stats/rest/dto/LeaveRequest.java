package me.prexorjustin.prexorcloud.modules.stats.rest.dto;

import java.time.Instant;
import java.util.UUID;

public record LeaveRequest(UUID sessionId, Instant quitAt, Long durationMs) {}
