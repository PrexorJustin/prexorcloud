package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.share.ShareKind;
import me.prexorjustin.prexorcloud.controller.share.ShareRecord;

/**
 * REST-facing view of a persisted {@link ShareRecord}. The upstream pste
 * {@code deleteToken} is intentionally <em>not</em> serialised — revocation is
 * mediated server-side via {@code POST /api/v1/shares/{id}/revoke}.
 */
public record ShareRecordDto(
        String id,
        ShareKind kind,
        String resourceId,
        String url,
        String rawUrl,
        Instant expiresAt,
        boolean burnAfterRead,
        boolean isPrivate,
        long sizeBytes,
        String sharedByUser,
        Instant sharedAt,
        Instant revokedAt,
        boolean revocable) {

    public static ShareRecordDto from(ShareRecord record) {
        boolean revocable = record.revokedAt() == null
                && record.deleteToken() != null
                && !record.deleteToken().isBlank();
        return new ShareRecordDto(
                record.id(),
                record.kind(),
                record.resourceId(),
                record.pasteUrl(),
                record.rawUrl(),
                record.expiresAt(),
                record.burnAfterRead(),
                record.isPrivate(),
                record.sizeBytes(),
                record.sharedByUser(),
                record.sharedAt(),
                record.revokedAt(),
                revocable);
    }
}
