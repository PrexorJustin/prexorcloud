package me.prexorjustin.prexorcloud.controller.share;

/**
 * Caller-identity sidecar threaded into every {@link ShareService} entry point.
 * Captured from the JWT context by the REST routes; supplied as {@link #system()}
 * by internal callers (controller-side scripts, tests).
 *
 * @param username  actor that initiated the action; stored on {@link ShareRecord} and the audit log
 * @param ipAddress client IP for the audit log; may be {@code null} when unavailable
 */
public record ShareContext(String username, String ipAddress) {

    private static final ShareContext SYSTEM = new ShareContext("system", null);

    public static ShareContext system() {
        return SYSTEM;
    }

    public static ShareContext of(String username, String ipAddress) {
        return new ShareContext(username == null || username.isBlank() ? "system" : username, ipAddress);
    }
}
