package me.prexorjustin.prexorcloud.controller.module.registry;

/**
 * Raised by {@link ModuleRegistryClient} for any registry-side failure: an index
 * that won't fetch/parse, an unknown module/version, a disallowed registry URL,
 * or a downloaded artifact whose SHA-256 doesn't match the index. Carries a
 * stable {@code code} so the REST layer can map it to a precise error response.
 */
public final class ModuleRegistryException extends RuntimeException {

    private final String code;

    public ModuleRegistryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ModuleRegistryException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
