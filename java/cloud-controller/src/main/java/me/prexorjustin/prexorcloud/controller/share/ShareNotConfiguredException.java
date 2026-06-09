package me.prexorjustin.prexorcloud.controller.share;

/**
 * Thrown when a {@code --share} request reaches {@link ShareService} while
 * {@code share.enabled=false}. Mapped to {@code 409 Conflict} by the REST
 * layer with a hint body explaining how to re-enable.
 */
public class ShareNotConfiguredException extends RuntimeException {

    public ShareNotConfiguredException() {
        super("Paste sharing is disabled (share.enabled=false)");
    }
}
