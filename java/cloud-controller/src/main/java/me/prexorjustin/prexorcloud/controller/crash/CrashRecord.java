package me.prexorjustin.prexorcloud.controller.crash;

import java.time.Instant;
import java.util.List;

/**
 * Record of a single instance crash.
 *
 * @param causeSummary
 *            one-line human-readable cause extracted from {@code logTail}
 *            (e.g. {@code "OutOfMemoryError: Java heap space"}).
 * @param signature
 *            deterministic short hash that groups recurring crashes with the
 *            same underlying cause for trend reporting.
 */
public record CrashRecord(
        String id,
        String instanceId,
        String group,
        String nodeId,
        int exitCode,
        String classification,
        String causeSummary,
        String signature,
        List<String> logTail,
        long uptimeMs,
        Instant crashedAt) {}
