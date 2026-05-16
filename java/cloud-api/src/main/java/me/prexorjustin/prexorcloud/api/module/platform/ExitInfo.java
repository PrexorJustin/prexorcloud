package me.prexorjustin.prexorcloud.api.module.platform;

/**
 * Process-exit summary handed to daemon modules in {@code onInstanceStopped}.
 *
 * <p>{@code crashSummary} is non-null when {@code crashed=true} and the daemon's crash
 * detector produced a one-line summary; otherwise it is {@code null}.
 */
public record ExitInfo(int exitCode, long durationMs, boolean crashed, String crashSummary) {}
