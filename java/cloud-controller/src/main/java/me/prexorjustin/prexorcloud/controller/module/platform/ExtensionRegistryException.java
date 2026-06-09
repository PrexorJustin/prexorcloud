package me.prexorjustin.prexorcloud.controller.module.platform;

/**
 * Registry validation or resolution failure for workload extensions.
 */
public final class ExtensionRegistryException extends RuntimeException {

    public ExtensionRegistryException(String message) {
        super(message);
    }
}
