package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;

public record HealingActionIntent(String instanceId, String groupName, String reason, Instant createdAt) {}
