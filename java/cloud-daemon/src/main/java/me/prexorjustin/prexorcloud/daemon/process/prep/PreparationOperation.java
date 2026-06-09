package me.prexorjustin.prexorcloud.daemon.process.prep;

/**
 * The functional shape of a single preparation step the daemon's retry
 * helper drives — see {@code ProcessManager.withPreparationRetries}.
 *
 * <p>Each implementation is one I/O-heavy unit of work (a download, a
 * filesystem move, a hash check). Returning {@code T} keeps the helper
 * generic over both side-effect-only and computed-value steps.
 *
 * <p>The {@link Exception} signature is intentional — the retry helper
 * inspects the throwable to decide retryable-vs-permanent (see
 * {@code ProcessManager.isRetryablePreparationFailure}).
 */
@FunctionalInterface
public interface PreparationOperation<T> {
    T run() throws Exception;
}
