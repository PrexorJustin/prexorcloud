package me.prexorjustin.prexorcloud.controller.share;

import java.time.Instant;

/**
 * Service-layer share result — surfaced to REST callers via {@code ShareResultDto}.
 *
 * <p>
 * Includes the upstream {@code deleteToken} alongside a fully-formed
 * {@code deleteUrl} so callers (CLI, dashboard) can print a one-shot revoke
 * link next to the share link. {@code deleteUrl} is {@code null} when the
 * upstream did not return a delete token.
 * </p>
 */
public record ShareResult(
        String shareId,
        String url,
        String rawUrl,
        Instant expiresAt,
        boolean isPrivate,
        boolean burnAfterRead,
        String deleteToken,
        String deleteUrl) {}
