package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.share.ShareResult;

/**
 * REST-facing result of a share invocation. Carries the upstream pste
 * {@code deleteToken} plus a fully-formed {@code deleteUrl} so callers (CLI,
 * dashboard) can print or wire a one-shot revoke action next to the share
 * link. {@code deleteUrl} / {@code deleteToken} are {@code null} when the
 * upstream did not return one.
 */
public record ShareResultDto(
        String shareId,
        String url,
        String rawUrl,
        Instant expiresAt,
        boolean isPrivate,
        boolean burnAfterRead,
        String deleteToken,
        String deleteUrl) {

    public static ShareResultDto from(ShareResult result) {
        return new ShareResultDto(
                result.shareId(),
                result.url(),
                result.rawUrl(),
                result.expiresAt(),
                result.isPrivate(),
                result.burnAfterRead(),
                result.deleteToken(),
                result.deleteUrl());
    }
}
