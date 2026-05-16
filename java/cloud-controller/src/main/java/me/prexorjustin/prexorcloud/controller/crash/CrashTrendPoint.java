package me.prexorjustin.prexorcloud.controller.crash;

import java.time.Instant;

/** Lightweight projection of a crash for trend aggregation. */
public record CrashTrendPoint(Instant crashedAt, String classification) {}
