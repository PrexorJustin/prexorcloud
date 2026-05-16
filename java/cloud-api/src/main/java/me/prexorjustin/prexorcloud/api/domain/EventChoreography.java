package me.prexorjustin.prexorcloud.api.domain;

/**
 * A time-bound overlay applied to a group on a cron schedule. Each entry fires
 * at the configured cron time, stays active for {@code durationSeconds}, and
 * temporarily replaces selected fields of the target group's resolved config.
 *
 * <p>Overlays compose with parent inheritance and never mutate the persisted
 * group; they're applied in-memory at scheduler tick time. When multiple
 * overlays target the same group, the one with the most recent firing window
 * wins (last-write-wins on the active window).
 *
 * @param name              unique entry identifier; matches {@code [a-z0-9_][a-z0-9_-]*}
 * @param description       human-readable description (may be empty)
 * @param group             target group name (must exist when the entry fires)
 * @param cron              5-field cron expression: {@code "m h dom mon dow"}.
 *                          Each field supports {@code *}, comma lists, dashed
 *                          ranges, and {@code /step}. No seconds, no aliases.
 * @param timezone          IANA zone ID for cron evaluation (e.g.
 *                          {@code "UTC"}, {@code "Europe/Berlin"}). Defaults to
 *                          {@code UTC} when blank.
 * @param durationSeconds   how long the overlay stays active after each firing
 * @param overlay           the partial group overlay applied during the window
 */
public record EventChoreography(
        String name,
        String description,
        String group,
        String cron,
        String timezone,
        long durationSeconds,
        EventOverlay overlay) {

    public EventChoreography {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        if (!name.matches("[a-z0-9_][a-z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid event name: " + name + " (must match [a-z0-9_][a-z0-9_-]*)");
        }
        if (description == null) description = "";
        if (group == null || group.isBlank()) throw new IllegalArgumentException("group");
        if (cron == null || cron.isBlank()) throw new IllegalArgumentException("cron");
        if (timezone == null || timezone.isBlank()) timezone = "UTC";
        if (durationSeconds <= 0) throw new IllegalArgumentException("durationSeconds must be > 0");
        if (overlay == null) throw new IllegalArgumentException("overlay");
    }

    /**
     * Partial group overlay. {@code null} on a field means "leave it alone".
     * At least one field must be non-null or the entry is rejected as a no-op.
     *
     * @param minInstances        overlay group {@code minInstances} (must be >= 0 if set)
     * @param maxInstances        overlay group {@code maxInstances} (must be > 0 if set)
     * @param scalingMode         overlay group {@code scalingMode}
     *                            ({@code DYNAMIC}/{@code STATIC}/{@code MANUAL})
     * @param maintenance         overlay group {@code maintenance} flag
     * @param maintenanceMessage  overlay group {@code maintenanceMessage}
     */
    public record EventOverlay(
            Integer minInstances,
            Integer maxInstances,
            String scalingMode,
            Boolean maintenance,
            String maintenanceMessage) {

        public EventOverlay {
            if (minInstances == null
                    && maxInstances == null
                    && scalingMode == null
                    && maintenance == null
                    && (maintenanceMessage == null || maintenanceMessage.isBlank())) {
                throw new IllegalArgumentException("overlay must set at least one field");
            }
            if (minInstances != null && minInstances < 0) {
                throw new IllegalArgumentException("overlay.minInstances must be >= 0");
            }
            if (maxInstances != null && maxInstances <= 0) {
                throw new IllegalArgumentException("overlay.maxInstances must be > 0");
            }
            if (scalingMode != null) {
                String normalized = scalingMode.toUpperCase(java.util.Locale.ROOT);
                if (!normalized.equals("DYNAMIC") && !normalized.equals("STATIC") && !normalized.equals("MANUAL")) {
                    throw new IllegalArgumentException(
                            "overlay.scalingMode must be DYNAMIC, STATIC, or MANUAL (got: " + scalingMode + ")");
                }
                scalingMode = normalized;
            }
        }
    }
}
