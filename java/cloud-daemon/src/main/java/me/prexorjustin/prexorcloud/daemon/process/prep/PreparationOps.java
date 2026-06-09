package me.prexorjustin.prexorcloud.daemon.process.prep;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

import me.prexorjustin.prexorcloud.common.util.HashUtil;
import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.protocol.StartPreparationStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static utilities shared by every preparation collaborator
 * ({@code ArtifactProvisioner}, {@code TemplatePreparation}, future
 * extractions). All pure functions — no instance state.
 *
 * <p>Extracted from {@code ProcessManager} so collaborators in this
 * package can call them without depending on the manager class. The
 * names + behaviour are identical to the original methods; the only
 * change is location.
 */
public final class PreparationOps {

    private static final Logger logger = LoggerFactory.getLogger(PreparationOps.class);

    /** Maximum attempts the retry helper makes before giving up. */
    public static final int PREPARATION_RETRY_ATTEMPTS = 3;

    /** Pause between retry attempts in milliseconds. */
    public static final long PREPARATION_RETRY_DELAY_MILLIS = 250;

    private PreparationOps() {}

    // ──────────────────────────────────────────────────────────────────
    // Retry helper — drives every preparation step that might hit a
    // transient I/O failure (network, ENOSPC, EAGAIN-on-rename, etc.)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Run {@code action} up to {@link #PREPARATION_RETRY_ATTEMPTS} times,
     * sleeping {@link #PREPARATION_RETRY_DELAY_MILLIS} between attempts.
     * Failures classified non-retryable by
     * {@link #isRetryablePreparationFailure(Throwable)} fail fast — they
     * won't change on a retry.
     */
    public static <T> T withPreparationRetries(String operation, PreparationOperation<T> action) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= PREPARATION_RETRY_ATTEMPTS; attempt++) {
            try {
                return action.run();
            } catch (Exception e) {
                lastFailure = e;
                if (!isRetryablePreparationFailure(e) || attempt >= PREPARATION_RETRY_ATTEMPTS) {
                    throw e;
                }
                logger.warn(
                        "{} failed on attempt {}/{}: {}",
                        operation,
                        attempt,
                        PREPARATION_RETRY_ATTEMPTS,
                        e.getMessage());
                try {
                    Thread.sleep(PREPARATION_RETRY_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    interrupted.addSuppressed(e);
                    throw interrupted;
                }
            }
        }
        throw lastFailure == null ? new IllegalStateException("retry helper exited without result") : lastFailure;
    }

    /**
     * Reports whether {@code failure} represents a transient condition
     * worth retrying. {@link SecurityException} (hash mismatch),
     * {@link IllegalArgumentException} (validation), and
     * {@link InvalidPathException} fail fast. {@link IOException} is
     * treated transient. Other throwables drill into their cause chain.
     */
    public static boolean isRetryablePreparationFailure(Throwable failure) {
        if (failure == null) {
            return false;
        }
        if (failure instanceof SecurityException
                || failure instanceof IllegalArgumentException
                || failure instanceof InvalidPathException) {
            return false;
        }
        if (failure instanceof InterruptedException) {
            return false;
        }
        if (failure instanceof IOException) {
            return true;
        }
        return isRetryablePreparationFailure(failure.getCause());
    }

    // ──────────────────────────────────────────────────────────────────
    // Filesystem helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Hardlink {@code source} to {@code target}; fall back to
     * {@code Files.copy(REPLACE_EXISTING)} if the filesystem doesn't
     * support hardlinks. Used when promoting prepared artefacts (cached
     * jars) into the live instance dir without paying the copy cost.
     */
    public static void linkOrCopy(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source);
        } catch (UnsupportedOperationException | IOException _) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Network helper
    // ──────────────────────────────────────────────────────────────────

    /**
     * Download {@code downloadUrl} to {@code target}, optionally enforcing
     * a SHA-256 hash. On hash mismatch the partial file is deleted and a
     * {@link SecurityException} is thrown — non-retryable per the policy
     * above, since the controller advertised a corrupted artefact.
     *
     * @param target       destination file
     * @param downloadUrl  https URL of the artefact
     * @param expectedHash optional SHA-256; null skips the check
     * @param label        human-readable label for error messages
     */
    public static void downloadToFile(Path target, String downloadUrl, String expectedHash, String label)
            throws Exception {
        try (var client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()) {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();
            client.send(req, HttpResponse.BodyHandlers.ofFile(target));
        }

        if (expectedHash != null) {
            String actualHash = HashUtil.sha256(target);
            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                Files.deleteIfExists(target);
                throw new SecurityException(
                        "hash mismatch for " + label + ": expected " + expectedHash + " but got " + actualHash);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Validation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Reject filesystem-unsafe values (path traversal, NUL bytes, etc.).
     * Throws {@link StartPreparationException} at the
     * {@link StartPreparationStage#VALIDATION} stage with a label-derived
     * error code so the controller can present a useful diagnostic.
     */
    public static void validateSafeName(String value, String label) throws StartPreparationException {
        if (!InputValidator.isSafeName(value)) {
            throw new StartPreparationException(
                    StartPreparationStage.VALIDATION,
                    "INVALID_" + label.toUpperCase(Locale.ROOT).replace(' ', '_'),
                    "",
                    "Invalid " + label + ": " + value);
        }
    }

    /** Treat blank strings as missing — the cache layers expect null, not "". */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ──────────────────────────────────────────────────────────────────
    // Exception factories
    // ──────────────────────────────────────────────────────────────────

    public static StartPreparationException stageFailure(
            StartPreparationStage stage, String errorCode, String planHash, String message) {
        return new StartPreparationException(stage, errorCode, planHash, message);
    }

    public static StartPreparationException stageFailure(
            StartPreparationStage stage, String errorCode, String planHash, String message, Throwable cause) {
        return new StartPreparationException(stage, errorCode, planHash, message, cause);
    }
}
