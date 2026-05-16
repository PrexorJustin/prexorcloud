package me.prexorjustin.prexorcloud.proxy.shared;

import java.util.UUID;

/** A single player's latency sample, including username for human-readable display. */
public record PlayerPingSample(UUID uuid, String username, int pingMs) {}
