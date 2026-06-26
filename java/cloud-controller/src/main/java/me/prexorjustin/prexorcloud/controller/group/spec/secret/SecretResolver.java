package me.prexorjustin.prexorcloud.controller.group.spec.secret;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Routes a {@code SECRET}-typed variable's reference to the {@link SecretBackend} that owns its scheme
 * and returns the resolved plaintext. This is the single entry point the dispatch path calls; it is
 * invoked once per secret, last-moment, into the transient start message — never at set time and never
 * into a persisted plan/snapshot/audit (see {@link SecretBackend}).
 *
 * <p>Reference grammar:
 * <ul>
 *   <li>{@code scheme://reference} — routed to the backend registered for {@code scheme}; an
 *       unregistered scheme is a hard error (so a typo'd backend never ships a server with a bogus
 *       secret).</li>
 *   <li>a value with no {@code ://} — an inline literal secret, returned unchanged (the operator's
 *       explicit choice; the dashboard still masks it).</li>
 *   <li>null/blank — returned unchanged (nothing to resolve).</li>
 * </ul>
 */
public final class SecretResolver {

    private static final String SCHEME_SEPARATOR = "://";

    private final Map<String, SecretBackend> backendsByScheme;

    public SecretResolver(List<SecretBackend> backends) {
        Map<String, SecretBackend> byScheme = new LinkedHashMap<>();
        for (SecretBackend backend : backends) {
            byScheme.put(backend.scheme().toLowerCase(Locale.ROOT), backend);
        }
        this.backendsByScheme = Map.copyOf(byScheme);
    }

    /** The built-in resolver: {@code env://} and {@code file://}. Bootstrap may build a richer one. */
    public static SecretResolver withDefaults() {
        return new SecretResolver(List.of(new EnvSecretBackend(), new FileSecretBackend()));
    }

    /**
     * Resolve a secret reference to its plaintext value. See the class doc for the reference grammar.
     *
     * @throws SecretResolutionException
     *             if the reference names an unregistered scheme or its backend fails
     */
    public String resolve(String reference) throws SecretResolutionException {
        if (reference == null || reference.isBlank()) {
            return reference;
        }
        int sep = reference.indexOf(SCHEME_SEPARATOR);
        if (sep <= 0) {
            return reference; // inline literal secret
        }
        String scheme = reference.substring(0, sep).toLowerCase(Locale.ROOT);
        String rest = reference.substring(sep + SCHEME_SEPARATOR.length());
        SecretBackend backend = backendsByScheme.get(scheme);
        if (backend == null) {
            throw new SecretResolutionException("no secret backend registered for scheme '" + scheme + "'");
        }
        return backend.resolve(rest);
    }
}
