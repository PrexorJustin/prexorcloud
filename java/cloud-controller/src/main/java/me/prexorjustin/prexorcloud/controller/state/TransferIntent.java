package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.UUID;

public record TransferIntent(UUID playerUuid, String targetInstanceId, Instant createdAt) {}
