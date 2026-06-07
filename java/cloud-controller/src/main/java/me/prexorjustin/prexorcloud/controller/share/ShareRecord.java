package me.prexorjustin.prexorcloud.controller.share;

import java.time.Instant;

/**
 * Persisted record of a single {@code --share} invocation. Backs the
 * {@code GET /api/v1/shares} list, the {@code POST /api/v1/shares/{id}/revoke}
 * action, and the per-share detail endpoint. {@code deleteToken} is kept
 * server-side and never echoed back to the dashboard/CLI; revocation is
 * mediated by the controller using this token internally.
 *
 * @param id            stable controller-generated UUID (also Mongo {@code _id})
 * @param kind          surface that originated the share
 * @param resourceId    crash id / node id / instance id depending on {@code kind}; may be {@code null}
 * @param pasteUrl      human-facing pste URL
 * @param rawUrl        raw-text pste URL
 * @param deleteToken   upstream delete token (sensitive — never serialised to wire DTOs)
 * @param expiresAt     absolute expiry; {@code null} for {@code expiry=never}
 * @param burnAfterRead echoed upstream burnAfterRead flag
 * @param isPrivate     visibility flag (stored locally — pste itself stores nothing)
 * @param sizeBytes     UTF-8 byte length of the redacted body that was uploaded
 * @param sharedByUser  username that initiated the share (from JWT)
 * @param sharedAt      controller wall-clock at upload-success time
 * @param revokedAt     wall-clock when the controller successfully called pste DELETE; {@code null} until revoked
 */
public record ShareRecord(
        String id,
        ShareKind kind,
        String resourceId,
        String pasteUrl,
        String rawUrl,
        String deleteToken,
        Instant expiresAt,
        boolean burnAfterRead,
        boolean isPrivate,
        long sizeBytes,
        String sharedByUser,
        Instant sharedAt,
        Instant revokedAt) {

    public ShareRecord withRevokedAt(Instant when) {
        return new ShareRecord(
                id,
                kind,
                resourceId,
                pasteUrl,
                rawUrl,
                deleteToken,
                expiresAt,
                burnAfterRead,
                isPrivate,
                sizeBytes,
                sharedByUser,
                sharedAt,
                when);
    }

    /** True when the controller has not yet recorded a successful revoke. */
    public boolean isActive() {
        return revokedAt == null;
    }
}
