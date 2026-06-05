package me.prexorjustin.prexorcloud.modules.backup.platform;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Periodic-snapshot schedule for the backup-orchestrator, parsed from environment variables.
 *
 * <p>v1 exposed REST triggers only; this adds an opt-in periodic path for operators who run a fixed
 * set of long-lived instances (a persistent lobby, a hub). Because modules can't enumerate live
 * instances from this context, targets are configured explicitly rather than discovered:
 *
 * <ul>
 *   <li>{@code PREXORCLOUD_BACKUP_INTERVAL_MINUTES} — snapshot period; {@code 0}/absent disables.
 *   <li>{@code PREXORCLOUD_BACKUP_INITIAL_DELAY_MINUTES} — delay before the first run (default 1).
 *   <li>{@code PREXORCLOUD_BACKUP_TARGETS} — comma-separated {@code nodeId/group/instanceId} triples.
 * </ul>
 *
 * <p>The schedule is {@link #enabled()} only when a positive interval <em>and</em> at least one
 * well-formed target are present — otherwise the module stays REST-only. Parsing is total: malformed
 * target tokens are skipped, not fatal.
 */
public record BackupSchedule(boolean enabled, Duration initialDelay, Duration period, List<Target> targets) {

    public record Target(String nodeId, String group, String instanceId) {}

    static final String INTERVAL_ENV = "PREXORCLOUD_BACKUP_INTERVAL_MINUTES";
    static final String INITIAL_DELAY_ENV = "PREXORCLOUD_BACKUP_INITIAL_DELAY_MINUTES";
    static final String TARGETS_ENV = "PREXORCLOUD_BACKUP_TARGETS";

    static BackupSchedule disabled() {
        return new BackupSchedule(false, Duration.ZERO, Duration.ZERO, List.of());
    }

    static BackupSchedule fromEnv() {
        return parse(System.getenv(INTERVAL_ENV), System.getenv(INITIAL_DELAY_ENV), System.getenv(TARGETS_ENV));
    }

    /**
     * Build a schedule from the raw env-var strings (any may be {@code null}). Kept pure so it can be
     * unit-tested without touching the process environment.
     */
    static BackupSchedule parse(String intervalMinutesRaw, String initialDelayMinutesRaw, String targetsRaw) {
        long interval = parseLong(intervalMinutesRaw, 0);
        long initial = parseLong(initialDelayMinutesRaw, 1);
        List<Target> targets = parseTargets(targetsRaw);
        boolean enabled = interval > 0 && !targets.isEmpty();
        if (!enabled) {
            return disabled();
        }
        return new BackupSchedule(
                true, Duration.ofMinutes(Math.max(0, initial)), Duration.ofMinutes(interval), targets);
    }

    static List<Target> parseTargets(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Target> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("/", -1);
            if (parts.length != 3) {
                continue; // not a node/group/instance triple — skip rather than fail the whole schedule
            }
            String node = parts[0].trim();
            String group = parts[1].trim();
            String instance = parts[2].trim();
            if (node.isEmpty() || instance.isEmpty()) {
                continue; // group may be blank (ungrouped instance); node + instance are required
            }
            out.add(new Target(node, group, instance));
        }
        return List.copyOf(out);
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
