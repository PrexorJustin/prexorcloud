package me.prexorjustin.prexorcloud.controller.share;

/**
 * Thrown by {@link ShareService#revoke(String, ShareContext)} when the
 * persisted record has no delete token (either pste did not return one, or the
 * record predates the deleteToken plumbing). Mapped to {@code HTTP 422} by the
 * REST layer.
 */
public class ShareNotRevocableException extends RuntimeException {
    public ShareNotRevocableException(String id) {
        super("Share " + id + " has no delete token and cannot be revoked");
    }
}
