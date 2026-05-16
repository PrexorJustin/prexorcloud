package me.prexorjustin.prexorcloud.controller.share;

import java.time.Instant;

/**
 * Response payload returned by {@link PasteClient#create(String, PasteOptions)}
 * on a successful {@code 201 Created}. Fields mirror the pste response body.
 *
 * @param id             paste id
 * @param url            human-facing URL
 * @param rawUrl         raw-content URL
 * @param deleteToken    delete token (held server-side; not exposed in the share-result DTO in MVP)
 * @param expiresAt      absolute expiry time, or {@code null} for {@code never}
 * @param language       echoed language tag
 * @param burnAfterRead  echoed burn-after-read flag
 */
public record PasteResult(
        String id,
        String url,
        String rawUrl,
        String deleteToken,
        Instant expiresAt,
        String language,
        boolean burnAfterRead) {}
