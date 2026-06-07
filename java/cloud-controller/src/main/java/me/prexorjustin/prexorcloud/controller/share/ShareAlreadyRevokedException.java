package me.prexorjustin.prexorcloud.controller.share;

/**
 * Thrown by {@link ShareService#revoke(String, ShareContext)} when the record
 * has already been revoked. Mapped to {@code HTTP 409} by the REST layer.
 */
public class ShareAlreadyRevokedException extends RuntimeException {
    public ShareAlreadyRevokedException(String id) {
        super("Share " + id + " is already revoked");
    }
}
