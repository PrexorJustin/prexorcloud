package me.prexorjustin.prexorcloud.api.module.platform;

import java.time.Instant;

/**
 * Read-only view of a running instance handed to daemon modules in
 * {@code onInstanceStarted}/{@code onInstanceStopping}/{@code onInstanceStopped}.
 *
 * <p>{@code state} mirrors the daemon's local lifecycle state and is provided for
 * informational use; the authoritative cluster state lives on the controller.
 */
public record InstanceHandle(String instanceId, String group, int port, long pid, Instant startedAt, String state) {}
