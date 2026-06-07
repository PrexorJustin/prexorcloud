package me.prexorjustin.prexorcloud.modules.stats.data;

import java.time.Instant;

public record GroupStat(String group, long totalMs, int sessionCount, int uniquePlayers, Instant updatedAt) {}
