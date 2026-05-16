package me.prexorjustin.prexorcloud.modules.runtime;

/**
 * Validation/parsing failure for the replacement platform module manifest.
 */
public final class PlatformModuleManifestException extends RuntimeException {

    public PlatformModuleManifestException(String source, String message) {
        super("Invalid platform module manifest in '" + source + "': " + message);
    }

    public PlatformModuleManifestException(String source, String message, Throwable cause) {
        super("Invalid platform module manifest in '" + source + "': " + message, cause);
    }
}
