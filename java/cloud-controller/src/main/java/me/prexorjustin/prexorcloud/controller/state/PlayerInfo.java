package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.UUID;

public record PlayerInfo(
        UUID uuid, String name, String instanceId, String group, String proxyInstanceId, Instant connectedAt) {}
