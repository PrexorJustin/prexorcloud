package me.prexorjustin.prexorcloud.api.module.platform;

/**
 * Raised when a platform module writes through its scoped storage handle after
 * reaching a manifest-declared soft limit.
 */
public final class StorageQuotaExceededException extends RuntimeException {

    public StorageQuotaExceededException(String message) {
        super(message);
    }
}
