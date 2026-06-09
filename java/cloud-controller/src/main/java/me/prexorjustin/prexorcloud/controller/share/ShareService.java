package me.prexorjustin.prexorcloud.controller.share;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.config.ShareConfig;
import me.prexorjustin.prexorcloud.controller.crash.CrashRecord;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.observability.LogRedactor;
import me.prexorjustin.prexorcloud.controller.state.StateStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates {@code --share} requests: redacts the supplied text, enforces
 * the upload ceiling, delegates the upload to {@link PasteClient}, persists a
 * {@link ShareRecord} for later listing / revocation, and emits audit + metric
 * events.
 *
 * <p>
 * Redaction is unconditional — every text section that flows through this
 * service is run through {@link LogRedactor} before reaching pste, regardless
 * of caller. The {@link #BYTE_CEILING} is intentionally below the documented
 * 5 MB pste limit to leave headroom for upstream framing.
 * </p>
 *
 * <p>
 * {@link StateStore} and {@link MetricsCollector} are {@code @Nullable} in the
 * constructor so the service remains unit-testable in isolation; production
 * wiring (in {@code PrexorCloudBootstrap}) always passes real instances.
 * </p>
 */
public final class ShareService {

    /** Hard ceiling on the redacted body size — keeps under pste's 5 MB limit. */
    public static final int BYTE_CEILING = 4 * 1024 * 1024;

    private static final Logger logger = LoggerFactory.getLogger(ShareService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private final ShareConfig config;
    private final PasteClient client;
    private final Clock clock;
    private final StateStore stateStore;
    private final MetricsCollector metrics;

    /** Test-friendly minimal ctor — no persistence, no metrics. */
    public ShareService(ShareConfig config, PasteClient client, Clock clock) {
        this(config, client, clock, null, null);
    }

    public ShareService(
            ShareConfig config, PasteClient client, Clock clock, StateStore stateStore, MetricsCollector metrics) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stateStore = stateStore;
        this.metrics = metrics;
    }

    /**
     * Share a crash record — header + redacted logTail. Defaults
     * {@code burnAfterRead=true} (single-reader operator → maintainer flow);
     * caller can override via {@link ShareRequest#burnAfterRead()}.
     */
    public ShareResult shareCrash(CrashRecord crash, ShareRequest request, ShareContext context) {
        requireEnabled();
        StringBuilder body = new StringBuilder(4 * 1024);
        body.append("# Crash report\n");
        body.append("crashId: ").append(safe(crash.id())).append('\n');
        body.append("instanceId: ").append(safe(crash.instanceId())).append('\n');
        body.append("group: ").append(safe(crash.group())).append('\n');
        body.append("nodeId: ").append(safe(crash.nodeId())).append('\n');
        body.append("exitCode: ").append(crash.exitCode()).append('\n');
        body.append("classification: ").append(safe(crash.classification())).append('\n');
        body.append("causeSummary: ").append(safe(crash.causeSummary())).append('\n');
        body.append("signature: ").append(safe(crash.signature())).append('\n');
        body.append("uptimeMs: ").append(crash.uptimeMs()).append('\n');
        body.append("crashedAt: ")
                .append(crash.crashedAt() == null ? "" : ISO.format(crash.crashedAt()))
                .append('\n');
        body.append("sharedAt: ").append(ISO.format(clock.instant())).append('\n');
        body.append('\n');
        body.append("# Log tail (redacted)\n");
        appendRedactedLines(body, crash.logTail());
        return upload(
                ShareKind.CRASH,
                safe(crash.id()),
                "crash-" + safe(crash.id()),
                body.toString(),
                request,
                BurnDefault.SINGLE_READER,
                context);
    }

    /**
     * Share a free-form set of log lines under a caller-provided title.
     * Defaults {@code burnAfterRead=true} (single-reader operator → maintainer
     * flow); caller can override via {@link ShareRequest#burnAfterRead()}.
     */
    public ShareResult shareLogText(
            ShareKind kind,
            String resourceId,
            String title,
            List<String> lines,
            ShareRequest request,
            ShareContext context) {
        requireEnabled();
        StringBuilder body = new StringBuilder(4 * 1024);
        body.append("# ")
                .append(title == null || title.isBlank() ? "Log share" : title)
                .append('\n');
        body.append("sharedAt: ").append(ISO.format(clock.instant())).append('\n');
        body.append('\n');
        appendRedactedLines(body, lines);
        return upload(
                kind,
                resourceId,
                title == null ? "log" : title,
                body.toString(),
                request,
                BurnDefault.SINGLE_READER,
                context);
    }

    /**
     * Share a pre-assembled, multi-section text document. Each line is run
     * through {@link LogRedactor} before upload. Intended for the expanded
     * diagnostics bundle (Phase 3) — diagnostics bundles routinely have
     * multiple readers, so {@code burnAfterRead} defaults off here via
     * {@link BurnDefault#MULTI_READER}. Caller can still flip it on via
     * {@link ShareRequest#burnAfterRead()}.
     */
    public ShareResult shareText(String title, String body, ShareRequest request, ShareContext context) {
        requireEnabled();
        StringBuilder out = new StringBuilder(Math.min(body == null ? 0 : body.length(), BYTE_CEILING));
        out.append("# ")
                .append(title == null || title.isBlank() ? "Share" : title)
                .append('\n');
        out.append("sharedAt: ").append(ISO.format(clock.instant())).append('\n');
        out.append('\n');
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n", -1)) {
                out.append(LogRedactor.redactLine(line)).append('\n');
            }
        }
        return upload(
                ShareKind.DIAGNOSTICS,
                null,
                title == null ? "share" : title,
                out.toString(),
                request,
                BurnDefault.MULTI_READER,
                context);
    }

    /**
     * Revoke a previously-shared paste. Calls pste {@code DELETE} using the
     * stored delete token, marks the local record revoked, audits, and emits
     * a metric.
     *
     * @throws NoSuchElementException        when no record exists for {@code id}
     * @throws ShareNotConfiguredException   when sharing is disabled
     * @throws ShareAlreadyRevokedException  when the record is already revoked
     * @throws ShareNotRevocableException    when no delete token was captured at upload time
     * @throws PasteException                on upstream failure
     */
    public ShareRecord revoke(String id, ShareContext context) {
        requireEnabled();
        ShareRecord existing = requireStore()
                .getShareRecord(id)
                .orElseThrow(() -> new NoSuchElementException("Share not found: " + id));
        if (existing.revokedAt() != null) {
            throw new ShareAlreadyRevokedException(id);
        }
        if (existing.deleteToken() == null || existing.deleteToken().isBlank()) {
            if (metrics != null) metrics.recordShareRevoke("missing-token");
            throw new ShareNotRevocableException(id);
        }
        try {
            client.delete(existing.deleteToken());
        } catch (PasteException e) {
            if (metrics != null) metrics.recordShareRevoke("error");
            throw e;
        }
        Instant when = clock.instant();
        requireStore().markShareRevoked(id, when);
        if (metrics != null) metrics.recordShareRevoke("success");
        ShareContext actor = context == null ? ShareContext.system() : context;
        requireStore()
                .audit(
                        actor.username(),
                        "REVOKE_SHARE",
                        "share",
                        id,
                        "kind=" + existing.kind().name() + " resourceId=" + nullSafe(existing.resourceId()),
                        actor.ipAddress());
        return existing.withRevokedAt(when);
    }

    private ShareResult upload(
            ShareKind kind,
            String resourceId,
            String title,
            String body,
            ShareRequest request,
            BurnDefault burnDefault,
            ShareContext context) {
        String bounded = enforceCeiling(body);
        long uploadBytes = bounded.getBytes(StandardCharsets.UTF_8).length;
        PasteOptions options = new PasteOptions(
                pickExpiry(request),
                "text",
                pickBurnAfterRead(request, burnDefault),
                UUID.randomUUID().toString());

        PasteResult result;
        try {
            result = client.create(bounded, options);
        } catch (PasteException e) {
            if (metrics != null) {
                metrics.recordShareAttempt(kind.name(), "error");
                metrics.recordShareUpstreamError(e.upstreamStatus());
            }
            throw e;
        }

        boolean isPrivate =
                request != null && request.visibility() != null ? request.visibility() : config.defaultPrivate();
        String deleteUrl = buildDeleteUrl(result.deleteToken());
        String shareId = UUID.randomUUID().toString();
        ShareContext actor = context == null ? ShareContext.system() : context;

        ShareRecord record = new ShareRecord(
                shareId,
                kind,
                resourceId,
                result.url(),
                result.rawUrl(),
                result.deleteToken(),
                result.expiresAt(),
                result.burnAfterRead(),
                isPrivate,
                uploadBytes,
                actor.username(),
                clock.instant(),
                null);
        if (stateStore != null) {
            try {
                stateStore.saveShareRecord(record);
                stateStore.audit(
                        actor.username(),
                        "CREATE_SHARE",
                        "share",
                        shareId,
                        "kind=" + kind.name() + " resourceId=" + nullSafe(resourceId)
                                + " bytes=" + uploadBytes
                                + " url=" + result.url(),
                        actor.ipAddress());
            } catch (RuntimeException persistErr) {
                // Upload already happened — log the persistence miss but still return success so the operator
                // sees the link. Revocation will be impossible for this share (no row), surfaced honestly in metrics.
                logger.warn(
                        "share record persist failed for {} (url={}): {}",
                        shareId,
                        result.url(),
                        persistErr.getMessage());
            }
        }
        if (metrics != null) {
            metrics.recordShareAttempt(kind.name(), "success");
            metrics.recordShareUploadBytes(uploadBytes);
        }
        logger.info(
                "shared {} kind={} → {} (expiry={}, burnAfterRead={}, bytes={}, deletable={}, by={})",
                title,
                kind,
                result.url(),
                options.expiry(),
                options.burnAfterRead(),
                uploadBytes,
                deleteUrl != null,
                actor.username());

        return new ShareResult(
                shareId,
                result.url(),
                result.rawUrl(),
                result.expiresAt(),
                isPrivate,
                result.burnAfterRead(),
                result.deleteToken(),
                deleteUrl);
    }

    private void requireEnabled() {
        if (!config.enabled()) throw new ShareNotConfiguredException();
    }

    private StateStore requireStore() {
        if (stateStore == null) {
            throw new IllegalStateException(
                    "ShareService was constructed without a StateStore — persistence-backed operations are unavailable");
        }
        return stateStore;
    }

    private String pickExpiry(ShareRequest request) {
        if (request != null && request.expiry() != null && !request.expiry().isBlank()) return request.expiry();
        return config.defaultExpiry();
    }

    /**
     * Pick the effective {@code burnAfterRead}: per-request caller override
     * wins; otherwise the per-surface {@link BurnDefault} encodes a
     * security-aware default (single-reader = on, multi-reader = off) that
     * surface-specific so a misconfig can never leave a crash/log share replayable.
     */
    private boolean pickBurnAfterRead(ShareRequest request, BurnDefault burnDefault) {
        if (request != null && request.burnAfterRead() != null) return request.burnAfterRead();
        return switch (burnDefault) {
            case SINGLE_READER -> true;
            case MULTI_READER -> false;
        };
    }

    private String buildDeleteUrl(String token) {
        if (token == null || token.isBlank()) return null;
        return stripTrailingSlash(config.pasteUrl()) + "/api/v1/paste/" + token;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) return url;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Per-surface default for {@code burnAfterRead}. Crash and log shares are
     * single-reader (operator → maintainer); diagnostics is multi-reader and
     * follows config.
     */
    private enum BurnDefault {
        SINGLE_READER,
        MULTI_READER
    }

    private static void appendRedactedLines(StringBuilder body, List<String> lines) {
        if (lines == null) return;
        for (String line : lines) {
            body.append(LogRedactor.redactLine(line == null ? "" : line)).append('\n');
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Enforce {@link #BYTE_CEILING} on the UTF-8 byte length of {@code body};
     * append a single truncation marker line when the limit is hit.
     */
    static String enforceCeiling(String body) {
        if (body == null) return "";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= BYTE_CEILING) return body;
        String marker = "\n[truncated — content exceeded " + BYTE_CEILING + " bytes]\n";
        byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);
        int budget = BYTE_CEILING - markerBytes.length;
        if (budget <= 0) return new String(markerBytes, StandardCharsets.UTF_8);
        // Truncate on a UTF-8 character boundary by trimming back from `budget` until
        // we land on a continuation-free byte (top bits != 10xxxxxx).
        int end = budget;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8) + marker;
    }
}
