package me.prexorjustin.prexorcloud.controller.share;

/**
 * Surface that originated a share invocation. Persisted on {@link ShareRecord}
 * so the "Recent shares" list can group/filter and the audit log carries a
 * meaningful resource type.
 */
public enum ShareKind {
    CRASH,
    CONTROLLER_LOGS,
    DAEMON_LOGS,
    DIAGNOSTICS,
    INSTANCE_CONSOLE
}
