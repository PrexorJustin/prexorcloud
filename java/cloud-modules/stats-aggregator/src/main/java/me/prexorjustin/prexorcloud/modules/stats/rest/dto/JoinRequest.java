package me.prexorjustin.prexorcloud.modules.stats.rest.dto;

import java.time.Instant;
import java.util.UUID;

public record JoinRequest(
        UUID playerId, String playerName, UUID sessionId, String group, String instanceId, Instant joinAt) {}
