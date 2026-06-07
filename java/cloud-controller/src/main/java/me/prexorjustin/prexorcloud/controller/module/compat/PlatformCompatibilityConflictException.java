package me.prexorjustin.prexorcloud.controller.module.compat;

/**
 * Thrown by the platform-module install/upgrade routes when the
 * compatibility report has at least one incompatible group.
 *
 * <p>Carries the full {@link PlatformCompatibilityReport} so the route
 * handler can return its JSON form to the operator without re-running
 * the evaluation.
 */
public final class PlatformCompatibilityConflictException extends IllegalStateException {

    private final PlatformCompatibilityReport report;

    public PlatformCompatibilityConflictException(String message, PlatformCompatibilityReport report) {
        super(message);
        this.report = report;
    }

    public PlatformCompatibilityReport report() {
        return report;
    }
}
